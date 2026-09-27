package com.example.adminauth.service.impl;

import com.example.adminauth.dto.audit.AuditEventDto;
import com.example.adminauth.entity.AuditEvent;
import com.example.adminauth.mapper.AuditMapper;
import com.example.adminauth.repository.AuditEventRepository;
import com.example.adminauth.service.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
            log.error("Failed to serialize audit event state", e);
        } catch (Exception e) {
            log.error("Failed to persist audit event", e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventDto> getAuditLogs(String actorId, String action, LocalDateTime start, LocalDateTime end, Pageable pageable) {
        Page<AuditEvent> page;
        if (actorId != null && !actorId.isBlank()) {
            page = auditEventRepository.findByActorIdOrderByCreatedAtDesc(actorId, pageable);
        } else if (action != null && !action.isBlank()) {
            page = auditEventRepository.findByActionOrderByCreatedAtDesc(action, pageable);
        } else if (start != null && end != null) {
            page = auditEventRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end, pageable);
        } else {
            page = auditEventRepository.findAll(pageable);
        }

        return page.map(auditMapper::toDto);
    }
}
