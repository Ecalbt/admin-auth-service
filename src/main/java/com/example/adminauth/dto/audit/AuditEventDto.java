package com.example.adminauth.dto.audit;

import lombok.Builder;

import java.time.LocalDateTime;

@Builder
public record AuditEventDto(
        Long id,
        String actorId,
        String action,
        String targetId,
        String beforeState,
        String afterState,
        String ipAddress,
        String userAgent,
        String correlationId,
        LocalDateTime createdAt
) {}
