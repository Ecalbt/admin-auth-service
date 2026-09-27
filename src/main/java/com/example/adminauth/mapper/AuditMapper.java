package com.example.adminauth.mapper;

import com.example.adminauth.dto.audit.AuditEventDto;
import com.example.adminauth.entity.AuditEvent;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mapper chuyển đổi AuditEvent entity sang AuditEventDto.
 */
@Component
public class AuditMapper {

    public AuditEventDto toDto(AuditEvent event) {
        if (event == null) return null;

        return AuditEventDto.builder()
                .id(event.getId())
                .actorId(event.getActorId())
                .action(event.getAction())
                .targetId(event.getTargetId())
                .beforeState(event.getBeforeState())
                .afterState(event.getAfterState())
                .ipAddress(event.getIpAddress())
                .userAgent(event.getUserAgent())
                .correlationId(event.getCorrelationId())
                .createdAt(event.getCreatedAt())
                .build();
    }

    public List<AuditEventDto> toDtoList(List<AuditEvent> events) {
        if (events == null) return List.of();
        return events.stream().map(this::toDto).toList();
    }
}
