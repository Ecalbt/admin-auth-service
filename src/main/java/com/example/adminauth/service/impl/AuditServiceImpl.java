package com.example.adminauth.service.impl;

import com.example.adminauth.dto.audit.AuditEventDto;
import com.example.adminauth.entity.AuditEvent;
import com.example.adminauth.mapper.AuditMapper;
import com.example.adminauth.repository.AuditEventRepository;
import com.example.adminauth.service.AuditService;
import com.example.adminauth.event.AuditEventType;
import com.example.adminauth.event.AuditEventV1;
import com.example.adminauth.event.EventSeverity;
import com.example.adminauth.messaging.OutboxWriter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditEventRepository auditEventRepository;
    private final AuditMapper auditMapper;
    private final ObjectMapper objectMapper;
    private final OutboxWriter outboxWriter;

    @Override
    @Transactional
    public void recordEvent(String actorId, String action, String targetId,
                            Object beforeState, Object afterState,
                            String ipAddress, String userAgent, String correlationId) {
        try {
            String beforeJson = beforeState != null ? (beforeState instanceof String s ? s : objectMapper.writeValueAsString(beforeState)) : null;
            String afterJson = afterState != null ? (afterState instanceof String s ? s : objectMapper.writeValueAsString(afterState)) : null;

            AuditEvent event = AuditEvent.builder()
                    .actorId(actorId != null ? actorId : "ANONYMOUS")
                    .action(action)
                    .targetId(targetId)
                    .beforeState(beforeJson)
                    .afterState(afterJson)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .correlationId(correlationId)
                    .build();

            auditEventRepository.save(event);
            log.info("AUDIT_LOG: actor='{}', action='{}', target='{}'", actorId, action, targetId);

            // Publish AuditEvent to Transactional Outbox
            AuditEventType eventType;
            try {
                eventType = AuditEventType.valueOf(action);
            } catch (Exception ex) {
                eventType = AuditEventType.LOGIN_SUCCESS;
            }

            EventSeverity severity;
            if ("TOKEN_REUSE_DETECTED".equals(action) || "ACCOUNT_LOCKED".equals(action)) {
                severity = EventSeverity.SECURITY;
            } else if ("PASSWORD_CHANGED".equals(action) || "PASSWORD_RESET".equals(action) ||
                    "ROLE_ASSIGNED".equals(action) || "ACCOUNT_DISABLED".equals(action) ||
                    "LOGIN_FAILED".equals(action) || "MFA_FAILED".equals(action)) {
                severity = EventSeverity.WARN;
            } else {
                severity = EventSeverity.INFO;
            }

            AuditEventV1 avroEvent = AuditEventV1.newBuilder()
                    .setEventId(UUID.randomUUID().toString())
                    .setEventType(eventType)
                    .setSeverity(severity)
                    .setActorId(actorId != null ? actorId : "ANONYMOUS")
                    .setActorUsername(null)
                    .setTargetId(targetId)
                    .setTargetType(determineTargetType(action))
                    .setBeforeState(beforeJson)
                    .setAfterState(afterJson)
                    .setIpAddress(ipAddress)
                    .setUserAgent(userAgent)
                    .setCorrelationId(correlationId)
                    .setOccurredAt(Instant.now())
                    .setServiceName("admin-auth-service")
                    .build();

            outboxWriter.writeAuditEvent(avroEvent);
        } catch (JsonProcessingException e) {
            log.error("[AUDIT_SERIALIZATION_ERROR] Failed to serialize audit event state for actor='{}', action='{}': {}",
                    actorId, action, e.getMessage(), e);
            throw new RuntimeException("Audit event serialization failure", e);
        } catch (Exception e) {
            log.error("[CRITICAL_SECURITY_ALERT] FAILED TO PERSIST AUDIT LOG! actor='{}', action='{}', target='{}': {}",
                    actorId, action, targetId, e.getMessage(), e);
            throw new RuntimeException("Audit event persistence failure", e);
        }
    }

    private String determineTargetType(String action) {
        if (action == null) return "UNKNOWN";
        if (action.contains("SESSION")) return "SESSION";
        if (action.contains("ROLE") || action.contains("PERMISSION")) return "ROLE_PERMISSION";
        if (action.contains("TOKEN")) return "TOKEN";
        if (action.contains("MFA")) return "MFA";
        return "ADMIN";
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventDto> getAuditLogs(String actorId, String action, LocalDateTime start, LocalDateTime end, Pageable pageable) {
        Specification<AuditEvent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorId != null && !actorId.isBlank()) {
                predicates.add(cb.equal(root.get("actorId"), actorId.trim()));
            }
            if (action != null && !action.isBlank()) {
                predicates.add(cb.equal(root.get("action"), action.trim()));
            }
            if (start != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), start));
            }
            if (end != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), end));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Page<AuditEvent> page = auditEventRepository.findAll(spec, pageable);
        return page.map(auditMapper::toDto);
    }
}
