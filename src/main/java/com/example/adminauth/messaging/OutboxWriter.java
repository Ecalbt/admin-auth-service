package com.example.adminauth.messaging;

import com.example.adminauth.entity.OutboxEvent;
import com.example.adminauth.entity.OutboxStatus;
import com.example.adminauth.event.AuditEventV1;
import com.example.adminauth.event.NotificationEventV1;
import com.example.adminauth.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Ghi event vào bảng outbox_events trong cùng transaction với nghiệp vụ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventMapper outboxEventMapper;

    @Value("${app.kafka.topics.audit-events:admin.auth.audit.events}")
    private String auditTopic;

    @Value("${app.kafka.topics.notification-events:admin.auth.notification.events}")
    private String notificationTopic;

    @Transactional
    public void writeAuditEvent(AuditEventV1 event) {
        String json = outboxEventMapper.toJson(event);
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(event.getEventId())
                .topic(auditTopic)
                .partitionKey(event.getActorId())
                .eventType(event.getEventType().name())
                .payload(json)
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(LocalDateTime.now())
                .build();
        outboxEventRepository.save(outboxEvent);
        log.debug("Outbox audit event queued: id={}, eventType={}", event.getEventId(), event.getEventType());
    }

    @Transactional
    public void writeNotificationEvent(NotificationEventV1 event) {
        String json = outboxEventMapper.toJson(event);
        OutboxEvent outboxEvent = OutboxEvent.builder()
                .id(event.getEventId())
                .topic(notificationTopic)
                .partitionKey(event.getRecipientId())
                .eventType(event.getEventType().name())
                .payload(json)
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(LocalDateTime.now())
                .build();
        outboxEventRepository.save(outboxEvent);
        log.debug("Outbox notification event queued: id={}, eventType={}", event.getEventId(), event.getEventType());
    }
}
