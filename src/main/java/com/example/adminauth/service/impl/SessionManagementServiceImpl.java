package com.example.adminauth.service.impl;

import com.example.adminauth.dto.session.AdminSessionDto;
import com.example.adminauth.repository.RefreshTokenRepository;
import com.example.adminauth.security.AdminPrincipal;
import com.example.adminauth.security.session.SessionRedisService;
import com.example.adminauth.service.AuditService;
import com.example.adminauth.service.SessionManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SessionManagementServiceImpl implements SessionManagementService {

    private final SessionRedisService sessionRedisService;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AuditService auditService;

    @Override
    public List<AdminSessionDto> getMySessions(AdminPrincipal actor) {
        return sessionRedisService.getSessionsForAdmin(actor.getId());
    }

    @Override
    public List<AdminSessionDto> getAdminSessions(String adminId, AdminPrincipal actor) {
        if (!adminId.equals(actor.getId())) {
            enforceSessionPermission(actor, "session:read_any");
        }
        return sessionRedisService.getSessionsForAdmin(adminId);
    }

    @Override
    @Transactional
    public void revokeSession(String adminId, String sessionId, AdminPrincipal actor) {
        if (!adminId.equals(actor.getId())) {
            enforceSessionPermission(actor, "session:revoke_any");
        }

        sessionRedisService.revokeSession(sessionId);
        refreshTokenRepository.revokeSession(sessionId, LocalDateTime.now());

        auditService.recordEvent(
                actor.getUsername(), "SESSION_REVOKED", adminId,
                null, "Revoked session: " + sessionId, null, null, null
        );
        log.info("Session '{}' for admin '{}' revoked by '{}'", sessionId, adminId, actor.getUsername());
    }

    @Override
    @Transactional
    public void revokeAllSessions(String adminId, AdminPrincipal actor) {
        if (!adminId.equals(actor.getId())) {
            enforceSessionPermission(actor, "session:revoke_any");
        }

        sessionRedisService.revokeAllSessionsForAdmin(adminId);
        refreshTokenRepository.revokeAllForAdmin(adminId, LocalDateTime.now());

        auditService.recordEvent(
                actor.getUsername(), "SESSION_REVOKED_ALL", adminId,
                null, "Revoked all sessions", null, null, null
        );
        log.info("All sessions for admin '{}' revoked by '{}'", adminId, actor.getUsername());
    }

    private void enforceSessionPermission(AdminPrincipal actor, String requiredPerm) {
        if (actor.getRoles() != null && actor.getRoles().contains("SUPERADMIN")) {
            return;
        }
        boolean hasPerm = actor.getPermissions() != null && actor.getPermissions().stream()
                .anyMatch(g -> "*".equals(g.perm()) || g.perm().equalsIgnoreCase(requiredPerm));

        if (!hasPerm) {
            throw new AccessDeniedException("Insufficient permission: " + requiredPerm + " required");
        }
    }
}
