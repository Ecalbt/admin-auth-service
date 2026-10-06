package com.example.adminauth.messaging;

import com.example.adminauth.event.NotificationChannel;
import com.example.adminauth.event.NotificationEventType;
import com.example.adminauth.event.NotificationEventV1;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service xây dựng NotificationEvent theo NotificationPolicy và ghi vào outbox_events.
 * Không gọi external service, chỉ INSERT trong transaction hiện tại.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final OutboxWriter outboxWriter;
    private final NotificationPolicy notificationPolicy;

    public void sendNotification(NotificationEventType eventType,
                                 String recipientId,
                                 String recipientUsername,
                                 String recipientEmail,
                                 Map<String, String> params,
                                 String actorId,
                                 String actorUsername,
                                 String reason,
                                 String correlationId) {

        List<NotificationChannel> channels = notificationPolicy.determineChannels(eventType);
        boolean sensitive = notificationPolicy.isSensitive(eventType);
        String templateCode = notificationPolicy.getTemplateCode(eventType);

        NotificationEventV1 event = NotificationEventV1.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setEventType(eventType)
                .setRecipientId(recipientId)
                .setRecipientUsername(recipientUsername != null ? recipientUsername : "")
                .setRecipientEmail(recipientEmail != null ? recipientEmail : "")
                .setChannels(channels)
                .setTemplateCode(templateCode)
                .setParams(params != null ? params : Collections.emptyMap())
                .setSensitive(sensitive)
                .setActorId(actorId)
                .setActorUsername(actorUsername)
                .setReason(reason)
                .setOccurredAt(Instant.now())
                .setCorrelationId(correlationId)
                .setServiceName("admin-auth-service")
                .build();

        outboxWriter.writeNotificationEvent(event);
        log.info("Notification dispatched to outbox: eventType={}, recipientId={}, channels={}",
                eventType, recipientId, channels);
    }
}
