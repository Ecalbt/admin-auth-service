package com.example.adminauth.service.impl;

import com.example.adminauth.dto.audit.AuditEventDto;
import com.example.adminauth.entity.AuditEvent;
import com.example.adminauth.mapper.AuditMapper;
import com.example.adminauth.repository.AuditEventRepository;
import com.example.adminauth.service.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditEventRepository auditEventRepository;
    private final AuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Async("taskExecutor")
    @Transactional
    public void recordEventAsync(String actorId, String action, String targetId,
                                 Object beforeState, Object afterState,
                                 String ipAddress, String userAgent, String correlationId) {
        recordEvent(actorId, action, targetId, beforeState, afterState, ipAddress, userAgent, correlationId);
    }

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
        } catch (JsonProcessingException e) {
            log.error("[AUDIT_SERIALIZATION_ERROR] Failed to serialize audit event state for actor='{}', action='{}': {}",
                    actorId, action, e.getMessage(), e);
        } catch (Exception e) {
            log.error("[CRITICAL_SECURITY_ALERT] FAILED TO PERSIST AUDIT LOG! actor='{}', action='{}', target='{}': {}",
                    actorId, action, targetId, e.getMessage(), e);
        }
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
