package com.example.adminauth.controller;

import com.example.adminauth.common.ApiResponse;
import com.example.adminauth.dto.audit.AuditEventDto;
import com.example.adminauth.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@Tag(name = "Audit", description = "Audit Log Query Endpoints")
@RestController
@RequestMapping("/v1/audit-events")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @Operation(summary = "Query system audit logs with filters and pagination")
    @PreAuthorize("@authz.hasPerm('audit:read')")
    @GetMapping
    public ApiResponse<Page<AuditEventDto>> getAuditEvents(
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @ParameterObject @PageableDefault(size = 50) Pageable pageable) {
        return ApiResponse.ok(auditService.getAuditLogs(actorId, action, start, end, pageable));
    }
}
