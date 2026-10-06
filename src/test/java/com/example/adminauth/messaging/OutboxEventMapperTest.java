package com.example.adminauth.messaging;

import com.example.adminauth.event.*;
import org.apache.avro.specific.SpecificRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventMapperTest {

    private final OutboxEventMapper mapper = new OutboxEventMapper();

    @Test
    @DisplayName("OutboxEventMapper serializes and deserializes AuditEventV1 accurately")
    void testAuditEventRoundtrip() {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        AuditEventV1 original = AuditEventV1.newBuilder()
                .setEventId(eventId)
                .setEventType(AuditEventType.LOGIN_SUCCESS)
                .setSeverity(EventSeverity.INFO)
                .setActorId("admin-123")
                .setActorUsername("superadmin")
                .setTargetId("admin-123")
                .setTargetType("ADMIN")
                .setBeforeState("{\"status\": \"PENDING\"}")
                .setAfterState("{\"status\": \"ACTIVE\"}")
                .setIpAddress("127.0.0.1")
                .setUserAgent("TestAgent/1.0")
                .setCorrelationId("corr-abc-123")
                .setOccurredAt(now)
                .setServiceName("admin-auth-service")
                .build();

        String json = mapper.toJson(original);
        assertThat(json).isNotBlank();
        assertThat(json).contains(eventId);

        SpecificRecord deserialized = mapper.fromJson("LOGIN_SUCCESS", "admin.auth.audit.events", json);
        assertThat(deserialized).isInstanceOf(AuditEventV1.class);

        AuditEventV1 result = (AuditEventV1) deserialized;
        assertThat(result.getEventId()).isEqualTo(eventId);
        assertThat(result.getEventType()).isEqualTo(AuditEventType.LOGIN_SUCCESS);
        assertThat(result.getSeverity()).isEqualTo(EventSeverity.INFO);
        assertThat(result.getActorId()).isEqualTo("admin-123");
        assertThat(result.getActorUsername()).isEqualTo("superadmin");
        assertThat(result.getTargetId()).isEqualTo("admin-123");
        assertThat(result.getTargetType()).isEqualTo("ADMIN");
        assertThat(result.getBeforeState()).isEqualTo("{\"status\": \"PENDING\"}");
        assertThat(result.getAfterState()).isEqualTo("{\"status\": \"ACTIVE\"}");
        assertThat(result.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(result.getUserAgent()).isEqualTo("TestAgent/1.0");
        assertThat(result.getCorrelationId()).isEqualTo("corr-abc-123");
        assertThat(result.getOccurredAt()).isEqualTo(now);
        assertThat(result.getServiceName()).isEqualTo("admin-auth-service");
    }

    @Test
    @DisplayName("OutboxEventMapper serializes and deserializes NotificationEventV1 accurately")
    void testNotificationEventRoundtrip() {
        String eventId = UUID.randomUUID().toString();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

        NotificationEventV1 original = NotificationEventV1.newBuilder()
                .setEventId(eventId)
                .setEventType(NotificationEventType.ADMIN_ACCOUNT_CREATED)
                .setRecipientId("recip-456")
                .setRecipientUsername("new_admin")
                .setRecipientEmail("new_admin@ocb.com.vn")
                .setChannels(List.of(NotificationChannel.EMAIL))
                .setTemplateCode("ADMIN_ACCOUNT_CREATED")
                .setParams(Map.of("temporaryPassword", "Temp#123456", "role", "OPERATIONS_ADMIN"))
                .setSensitive(true)
                .setActorId("superadmin-id")
                .setActorUsername("superadmin")
                .setReason("New onboard")
                .setOccurredAt(now)
                .setCorrelationId("corr-xyz")
                .setServiceName("admin-auth-service")
                .build();

        String json = mapper.toJson(original);
        assertThat(json).isNotBlank();
        assertThat(json).contains(eventId);

        SpecificRecord deserialized = mapper.fromJson("ADMIN_ACCOUNT_CREATED", "admin.auth.notification.events", json);
        assertThat(deserialized).isInstanceOf(NotificationEventV1.class);

        NotificationEventV1 result = (NotificationEventV1) deserialized;
        assertThat(result.getEventId()).isEqualTo(eventId);
        assertThat(result.getEventType()).isEqualTo(NotificationEventType.ADMIN_ACCOUNT_CREATED);
        assertThat(result.getRecipientId()).isEqualTo("recip-456");
        assertThat(result.getRecipientUsername()).isEqualTo("new_admin");
        assertThat(result.getRecipientEmail()).isEqualTo("new_admin@ocb.com.vn");
        assertThat(result.getChannels()).containsExactly(NotificationChannel.EMAIL);
        assertThat(result.getTemplateCode()).isEqualTo("ADMIN_ACCOUNT_CREATED");
        assertThat(result.getParams()).containsEntry("temporaryPassword", "Temp#123456");
        assertThat(result.getSensitive()).isTrue();
        assertThat(result.getActorId()).isEqualTo("superadmin-id");
        assertThat(result.getActorUsername()).isEqualTo("superadmin");
        assertThat(result.getReason()).isEqualTo("New onboard");
        assertThat(result.getOccurredAt()).isEqualTo(now);
        assertThat(result.getServiceName()).isEqualTo("admin-auth-service");
    }
}
