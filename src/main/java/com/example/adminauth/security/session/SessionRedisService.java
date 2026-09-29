package com.example.adminauth.security.session;

import com.example.adminauth.dto.session.AdminSessionDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class SessionRedisService {

    private static final String SESSION_KEY_PREFIX = "session:";
    private static final String ADMIN_SESSIONS_PREFIX = "admin_sessions:";

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    private final ObjectMapper objectMapper;

    @Value("${app.security.session-idle-timeout-minutes:30}")
    private long idleTimeoutMinutes;

    @Value("${app.security.max-concurrent-sessions:5}")
    private int maxConcurrentSessions;

    // In-memory fallback if Redis is unavailable or during lightweight testing
    private final Map<String, AdminSessionDto> fallbackSessionStore = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> fallbackAdminSessions = new ConcurrentHashMap<>();

    public SessionRedisService() {
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Autowired
    public SessionRedisService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public AdminSessionDto createSession(String adminId, String username, String ipAddress, String userAgent) {
        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(idleTimeoutMinutes));

        AdminSessionDto sessionDto = new AdminSessionDto(
                sessionId, adminId, username, ipAddress, userAgent, now, now, expiresAt
        );

        saveSession(sessionDto);
        enforceMaxConcurrentSessions(adminId);

        return sessionDto;
    }

    public boolean isValidSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }

        AdminSessionDto session = getSession(sessionId);
        if (session == null) {
            return false;
        }

        if (Instant.now().isAfter(session.expiresAt())) {
            revokeSession(sessionId);
            return false;
        }

        return true;
    }

    public void touchSession(String sessionId) {
        AdminSessionDto session = getSession(sessionId);
        if (session != null) {
            Instant now = Instant.now();
            Instant newExpires = now.plus(Duration.ofMinutes(idleTimeoutMinutes));
            AdminSessionDto updated = new AdminSessionDto(
                    session.sessionId(), session.adminId(), session.username(),
                    session.ipAddress(), session.userAgent(), session.createdAt(),
                    now, newExpires
            );
            saveSession(updated);
        }
    }

    public AdminSessionDto getSession(String sessionId) {
        if (sessionId == null) return null;

        if (redisTemplate != null) {
            try {
                String json = redisTemplate.opsForValue().get(SESSION_KEY_PREFIX + sessionId);
                if (json != null) {
                    return objectMapper.readValue(json, AdminSessionDto.class);
                }
            } catch (Exception e) {
                log.warn("Redis read failed for session {}, using fallback: {}", sessionId, e.getMessage());
            }
        }

        return fallbackSessionStore.get(sessionId);
    }

    public List<AdminSessionDto> getSessionsForAdmin(String adminId) {
        List<AdminSessionDto> result = new ArrayList<>();
        Set<String> sessionIds = getSessionIdsForAdmin(adminId);

        for (String sid : sessionIds) {
            AdminSessionDto session = getSession(sid);
            if (session != null && Instant.now().isBefore(session.expiresAt())) {
                result.add(session);
            } else {
                revokeSession(sid);
            }
        }

        result.sort(Comparator.comparing(AdminSessionDto::lastUsedAt).reversed());
        return result;
    }

    public void revokeSession(String sessionId) {
        AdminSessionDto session = getSession(sessionId);

        if (redisTemplate != null) {
            try {
                redisTemplate.delete(SESSION_KEY_PREFIX + sessionId);
                if (session != null) {
                    redisTemplate.opsForSet().remove(ADMIN_SESSIONS_PREFIX + session.adminId(), sessionId);
                }
            } catch (Exception e) {
                log.warn("Redis revoke failed for session {}: {}", sessionId, e.getMessage());
            }
        }

        fallbackSessionStore.remove(sessionId);
        if (session != null) {
            Set<String> sids = fallbackAdminSessions.get(session.adminId());
            if (sids != null) {
                sids.remove(sessionId);
            }
        }
    }

    public void revokeAllSessionsForAdmin(String adminId) {
        Set<String> sessionIds = getSessionIdsForAdmin(adminId);
        for (String sid : sessionIds) {
            revokeSession(sid);
        }

        if (redisTemplate != null) {
            try {
                redisTemplate.delete(ADMIN_SESSIONS_PREFIX + adminId);
            } catch (Exception e) {
                log.warn("Redis delete admin sessions failed: {}", e.getMessage());
            }
        }

        fallbackAdminSessions.remove(adminId);
    }

    private void saveSession(AdminSessionDto sessionDto) {
        String json;
        try {
            json = objectMapper.writeValueAsString(sessionDto);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Cannot serialize session to JSON", e);
        }

        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(
                        SESSION_KEY_PREFIX + sessionDto.sessionId(),
                        json,
                        Duration.ofMinutes(idleTimeoutMinutes)
                );
                redisTemplate.opsForSet().add(ADMIN_SESSIONS_PREFIX + sessionDto.adminId(), sessionDto.sessionId());
                return;
            } catch (Exception e) {
                log.warn("Redis save failed, writing to fallback memory: {}", e.getMessage());
            }
        }

        fallbackSessionStore.put(sessionDto.sessionId(), sessionDto);
        fallbackAdminSessions.computeIfAbsent(sessionDto.adminId(), k -> ConcurrentHashMap.newKeySet())
                .add(sessionDto.sessionId());
    }

    private Set<String> getSessionIdsForAdmin(String adminId) {
        if (redisTemplate != null) {
            try {
                Set<String> members = redisTemplate.opsForSet().members(ADMIN_SESSIONS_PREFIX + adminId);
                if (members != null && !members.isEmpty()) {
                    return members;
                }
            } catch (Exception e) {
                log.warn("Redis get session IDs failed, using fallback: {}", e.getMessage());
            }
        }

        return fallbackAdminSessions.getOrDefault(adminId, Collections.emptySet());
    }

    private void enforceMaxConcurrentSessions(String adminId) {
        List<AdminSessionDto> activeSessions = getSessionsForAdmin(adminId);
        if (activeSessions.size() > maxConcurrentSessions) {
            // Sort ascending by lastUsedAt to revoke oldest
            activeSessions.sort(Comparator.comparing(AdminSessionDto::lastUsedAt));
            int toRevoke = activeSessions.size() - maxConcurrentSessions;
            for (int i = 0; i < toRevoke; i++) {
                revokeSession(activeSessions.get(i).sessionId());
            }
        }
    }

    public void saveOnboardingToken(String adminId, String token, Duration ttl) {
        String key = "onboarding:" + adminId;
        if (redisTemplate != null) {
            try {
                redisTemplate.opsForValue().set(key, token, ttl);
                return;
            } catch (Exception e) {
                log.warn("Redis save onboarding token failed: {}", e.getMessage());
            }
        }
        fallbackSessionStore.put(key, new AdminSessionDto(token, adminId, null, null, null, Instant.now(), Instant.now(), Instant.now().plus(ttl)));
    }

    public boolean validateOnboardingToken(String adminId, String token) {
        if (adminId == null || token == null) return false;
        String key = "onboarding:" + adminId;
        if (redisTemplate != null) {
            try {
                String stored = redisTemplate.opsForValue().get(key);
                return token.equals(stored);
            } catch (Exception e) {
                log.warn("Redis validate onboarding token failed: {}", e.getMessage());
            }
        }
        AdminSessionDto dto = fallbackSessionStore.get(key);
        return dto != null && token.equals(dto.sessionId()) && Instant.now().isBefore(dto.expiresAt());
    }

    public void removeOnboardingToken(String adminId) {
        String key = "onboarding:" + adminId;
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("Redis delete onboarding token failed: {}", e.getMessage());
            }
        }
        fallbackSessionStore.remove(key);
    }
}
