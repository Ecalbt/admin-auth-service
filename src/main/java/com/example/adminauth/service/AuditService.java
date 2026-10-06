package com.example.adminauth.service;

import com.example.adminauth.dto.audit.AuditEventDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;

/**
 * Service Interface định nghĩa ghi và tra cứu nhật ký kiểm toán (Audit Trail).
 */
public interface AuditService {

    void recordEvent(String actorId, String action, String targetId,
                     Object beforeState, Object afterState,
                     String ipAddress, String userAgent, String correlationId);

    Page<AuditEventDto> getAuditLogs(String actorId, String action,
                                     LocalDateTime start, LocalDateTime end,
                                     Pageable pageable);
}
