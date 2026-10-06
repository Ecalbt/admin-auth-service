package com.example.adminauth.messaging;

import com.example.adminauth.entity.OutboxEvent;
import com.example.adminauth.entity.OutboxStatus;
import com.example.adminauth.event.*;
import com.example.adminauth.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxWriterTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @InjectMocks
    private OutboxWriter outboxWriter;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(outboxWriter, "auditTopic", "admin.auth.audit.events");
        ReflectionTestUtils.setField(outboxWriter, "notificationTopic", "admin.auth.notification.events");
    }

    @Test
    @DisplayName("OutboxWriter saves AuditEvent with status PENDING and attempts 0")
    void testWriteAuditEvent() {
        String eventId = UUID.randomUUID().toString();
        AuditEventV1 event = AuditEventV1.newBuilder()
                .setEventId(eventId)
                .setEventType(AuditEventType.LOGIN_SUCCESS)
                .setSeverity(EventSeverity.INFO)
                .setActorId("admin-001")
                .setActorUsername("superadmin")
                .setTargetId("admin-001")
                .setTargetType("ADMIN")
                .setBeforeState(null)
                .setAfterState(null)
                .setIpAddress("127.0.0.1")
                .setUserAgent("Browser")
                .setCorrelationId("c-1")
                .setOccurredAt(Instant.now())
                .setServiceName("admin-auth-service")
                .build();

        when(outboxEventMapper.toJson(event)).thenReturn("{\"eventId\":\"" + eventId + "\"}");

        outboxWriter.writeAuditEvent(event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(eventId);
        assertThat(saved.getTopic()).isEqualTo("admin.auth.audit.events");
        assertThat(saved.getPartitionKey()).isEqualTo("admin-001");
        assertThat(saved.getEventType()).isEqualTo("LOGIN_SUCCESS");
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAttempts()).isEqualTo(0);
        assertThat(saved.getPayload()).contains(eventId);
    }

    @Test
    @DisplayName("OutboxWriter saves NotificationEvent with status PENDING and recipientId as partitionKey")
    void testWriteNotificationEvent() {
        String eventId = UUID.randomUUID().toString();
        NotificationEventV1 event = NotificationEventV1.newBuilder()
                .setEventId(eventId)
                .setEventType(NotificationEventType.PASSWORD_RESET)
                .setRecipientId("admin-target-99")
                .setRecipientUsername("target_user")
                .setRecipientEmail("target@ocb.com.vn")
                .setChannels(List.of(NotificationChannel.EMAIL))
                .setTemplateCode("PASSWORD_RESET")
                .setParams(Map.of("temp", "123"))
                .setSensitive(true)
                .setActorId("actor-1")
                .setActorUsername("admin")
                .setReason(null)
                .setOccurredAt(Instant.now())
                .setCorrelationId("c-2")
                .setServiceName("admin-auth-service")
                .build();

        when(outboxEventMapper.toJson(event)).thenReturn("{\"eventId\":\"" + eventId + "\"}");

        outboxWriter.writeNotificationEvent(event);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());

        OutboxEvent saved = captor.getValue();
        assertThat(saved.getId()).isEqualTo(eventId);
        assertThat(saved.getTopic()).isEqualTo("admin.auth.notification.events");
        assertThat(saved.getPartitionKey()).isEqualTo("admin-target-99");
        assertThat(saved.getEventType()).isEqualTo("PASSWORD_RESET");
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAttempts()).isEqualTo(0);
    }
}
