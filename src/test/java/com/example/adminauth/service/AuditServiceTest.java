package com.example.adminauth.service;

import com.example.adminauth.dto.audit.AuditEventDto;
import com.example.adminauth.entity.AuditEvent;
import com.example.adminauth.mapper.AuditMapper;
import com.example.adminauth.repository.AuditEventRepository;
import com.example.adminauth.service.impl.AuditServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditEventRepository auditEventRepository;

    @Mock
    private AuditMapper auditMapper;

    @Mock
    private com.example.adminauth.messaging.OutboxWriter outboxWriter;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AuditServiceImpl auditService;

    @Test
    @DisplayName("AU-04: Record audit event captures actor, action, target, IP, and serialized states")
    void testRecordEventCapturesAllDetails() {
        auditService.recordEvent(
                "superadmin", "ACCOUNT_CREATED", "adm-123",
                "{\"before\": 1}", "{\"after\": 2}",
                "192.168.1.100", "Mozilla/5.0", "corr-999"
        );

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditEventRepository).save(captor.capture());

        AuditEvent event = captor.getValue();
        assertThat(event.getActorId()).isEqualTo("superadmin");
        assertThat(event.getAction()).isEqualTo("ACCOUNT_CREATED");
        assertThat(event.getTargetId()).isEqualTo("adm-123");
        assertThat(event.getIpAddress()).isEqualTo("192.168.1.100");
        assertThat(event.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(event.getCorrelationId()).isEqualTo("corr-999");
        assertThat(event.getBeforeState()).contains("\"before\": 1");
        assertThat(event.getAfterState()).contains("\"after\": 2");
    }

    @Test
    @DisplayName("AU-01 & AU-02: Query audit events passes dynamic Specification combining criteria with AND")
    void testGetAuditLogsWithFilter() {
        LocalDateTime start = LocalDateTime.now().minusDays(1);
        LocalDateTime end = LocalDateTime.now();
        Pageable pageable = PageRequest.of(0, 50);

        AuditEvent event = AuditEvent.builder()
                .actorId("ops_admin")
                .action("ACCOUNT_CREATED")
                .createdAt(LocalDateTime.now())
                .build();

        Page<AuditEvent> eventPage = new PageImpl<>(List.of(event));
        when(auditEventRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(eventPage);

        AuditEventDto dto = AuditEventDto.builder()
                .actorId("ops_admin")
                .action("ACCOUNT_CREATED")
                .build();
        when(auditMapper.toDto(event)).thenReturn(dto);

        Page<AuditEventDto> result = auditService.getAuditLogs("ops_admin", "ACCOUNT_CREATED", start, end, pageable);

        assertThat(result).isNotNull();
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).actorId()).isEqualTo("ops_admin");
        assertThat(result.getContent().get(0).action()).isEqualTo("ACCOUNT_CREATED");
        verify(auditEventRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    @DisplayName("RES-02 & GAP-09: Recording audit event fails fast and throws exception on DB failure to guarantee transaction rollback")
    void testRecordEventHandlesFailureGracefully() {
        when(auditEventRepository.save(any(AuditEvent.class)))
                .thenThrow(new RuntimeException("DB Connection Timeout"));

        assertThatThrownBy(() -> auditService.recordEvent(
                "superadmin", "ACCOUNT_DISABLED", "adm-bad",
                null, "Disabling malicious admin",
                "10.0.0.1", "Agent", null
        )).isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Audit event persistence failure");

        verify(auditEventRepository).save(any(AuditEvent.class));
    }
}
