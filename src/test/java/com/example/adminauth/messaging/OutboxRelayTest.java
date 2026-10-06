package com.example.adminauth.messaging;

import com.example.adminauth.entity.OutboxEvent;
import com.example.adminauth.entity.OutboxStatus;
import com.example.adminauth.event.AuditEventType;
import com.example.adminauth.event.AuditEventV1;
import com.example.adminauth.event.EventSeverity;
import com.example.adminauth.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private OutboxEventMapper outboxEventMapper;

    @Mock
    private KafkaEventPublisher kafkaEventPublisher;

    @Spy
    private SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(outboxRelay, "relayEnabled", true);
        ReflectionTestUtils.setField(outboxRelay, "batchSize", 100);
        ReflectionTestUtils.setField(outboxRelay, "maxAttempts", 10);
        outboxRelay.initMetrics();
    }

    @Test
    @DisplayName("OutboxRelay publishes pending batch and marks events PUBLISHED")
    void testProcessOutboxBatchSuccess() throws Exception {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .topic("admin.auth.audit.events")
                .partitionKey("actor-1")
                .eventType("LOGIN_SUCCESS")
                .payload("{\"eventId\":\"" + eventId + "\"}")
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .build();

        AuditEventV1 avroRecord = AuditEventV1.newBuilder()
                .setEventId(eventId)
                .setEventType(AuditEventType.LOGIN_SUCCESS)
                .setSeverity(EventSeverity.INFO)
                .setActorId("actor-1")
                .setOccurredAt(Instant.now())
                .setServiceName("ae-admin-auth-service")
                .build();

        when(outboxEventRepository.findPendingBatchForUpdateSkipLocked(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxEventMapper.fromJson(eq("LOGIN_SUCCESS"), eq("admin.auth.audit.events"), anyString()))
                .thenReturn(avroRecord);
        when(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(0L);

        outboxRelay.processOutboxBatch();

        verify(kafkaEventPublisher).publishSync(eq("admin.auth.audit.events"), eq("actor-1"), eq(avroRecord), eq(5L));
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("OutboxRelay increments attempts and schedules backoff retry upon Kafka error")
    void testProcessOutboxBatchRetryOnFailure() throws Exception {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .topic("admin.auth.audit.events")
                .partitionKey("actor-1")
                .eventType("LOGIN_SUCCESS")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .build();

        AuditEventV1 avroRecord = AuditEventV1.newBuilder()
                .setEventId(eventId)
                .setEventType(AuditEventType.LOGIN_SUCCESS)
                .setSeverity(EventSeverity.INFO)
                .setActorId("actor-1")
                .setOccurredAt(Instant.now())
                .setServiceName("ae-admin-auth-service")
                .build();

        when(outboxEventRepository.findPendingBatchForUpdateSkipLocked(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxEventMapper.fromJson(anyString(), anyString(), anyString())).thenReturn(avroRecord);
        doThrow(new RuntimeException("Broker connection timeout"))
                .when(kafkaEventPublisher).publishSync(anyString(), anyString(), any(), anyLong());
        when(outboxEventRepository.countByStatus(OutboxStatus.PENDING)).thenReturn(1L);

        outboxRelay.processOutboxBatch();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isNotNull();
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("OutboxRelay marks event as FAILED after max attempts exceeded")
    void testProcessOutboxBatchMarksFailedAfterMaxAttempts() throws Exception {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = OutboxEvent.builder()
                .id(eventId)
                .topic("admin.auth.audit.events")
                .partitionKey("actor-1")
                .eventType("LOGIN_SUCCESS")
                .payload("{}")
                .status(OutboxStatus.PENDING)
                .attempts(9) // next attempt will be 10 (max)
                .build();

        AuditEventV1 avroRecord = AuditEventV1.newBuilder()
                .setEventId(eventId)
                .setEventType(AuditEventType.LOGIN_SUCCESS)
                .setSeverity(EventSeverity.INFO)
                .setActorId("actor-1")
                .setOccurredAt(Instant.now())
                .setServiceName("ae-admin-auth-service")
                .build();

        when(outboxEventRepository.findPendingBatchForUpdateSkipLocked(any(LocalDateTime.class), eq(100)))
                .thenReturn(List.of(event));
        when(outboxEventMapper.fromJson(anyString(), anyString(), anyString())).thenReturn(avroRecord);
        doThrow(new RuntimeException("Fatal Kafka rejection"))
                .when(kafkaEventPublisher).publishSync(anyString(), anyString(), any(), anyLong());

        outboxRelay.processOutboxBatch();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getAttempts()).isEqualTo(10);
        verify(outboxEventRepository).save(event);
    }

    @Test
    @DisplayName("OutboxRelay does nothing when relay is disabled")
    void testDisabledRelayDoesNothing() {
        ReflectionTestUtils.setField(outboxRelay, "relayEnabled", false);

        outboxRelay.processOutboxBatch();

        verifyNoInteractions(outboxEventRepository);
        verifyNoInteractions(kafkaEventPublisher);
    }
}
