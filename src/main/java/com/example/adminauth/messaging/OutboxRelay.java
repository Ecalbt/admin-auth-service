package com.example.adminauth.messaging;

import com.example.adminauth.entity.OutboxEvent;
import com.example.adminauth.entity.OutboxStatus;
import com.example.adminauth.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.avro.specific.SpecificRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OutboxRelay chạy nền (polling với SKIP LOCKED) để đọc các event PENDING và đẩy lên Kafka.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxEventRepository outboxEventRepository;
    private final OutboxEventMapper outboxEventMapper;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final MeterRegistry meterRegistry;

    @Value("${app.outbox.relay.enabled:true}")
    private boolean relayEnabled;

    @Value("${app.outbox.relay.batch-size:100}")
    private int batchSize;

    @Value("${app.outbox.relay.max-attempts:10}")
    private int maxAttempts;

    private final AtomicLong backlogCount = new AtomicLong(0);

    @PostConstruct
    public void initMetrics() {
        Gauge.builder("outbox.backlog", backlogCount, AtomicLong::get)
                .description("Number of pending outbox events")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.fixed-delay-ms:500}")
    @Transactional
    public void processOutboxBatch() {
        if (!relayEnabled) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> batch = outboxEventRepository.findPendingBatchForUpdateSkipLocked(now, batchSize);
        if (batch.isEmpty()) {
            return;
        }

        for (OutboxEvent event : batch) {
            publishSingleEvent(event, now);
        }

        try {
            long pending = outboxEventRepository.countByStatus(OutboxStatus.PENDING);
            backlogCount.set(pending);
        } catch (Exception e) {
            log.debug("Could not update backlog metric: {}", e.getMessage());
        }
    }

    private void publishSingleEvent(OutboxEvent event, LocalDateTime now) {
        try {
            SpecificRecord avroRecord = outboxEventMapper.fromJson(event.getEventType(), event.getTopic(), event.getPayload());
            kafkaEventPublisher.publishSync(event.getTopic(), event.getPartitionKey(), avroRecord, 5);

            event.setStatus(OutboxStatus.PUBLISHED);
            event.setPublishedAt(LocalDateTime.now());
            outboxEventRepository.save(event);
            log.debug("Outbox event {} published successfully", event.getId());
        } catch (Exception e) {
            int attempts = event.getAttempts() + 1;
            event.setAttempts(attempts);

            if (attempts >= maxAttempts) {
                event.setStatus(OutboxStatus.FAILED);
                log.error("[CRITICAL_OUTBOX_ALERT] Outbox event {} reached max attempts ({}) and marked FAILED! Topic={}, Key={}: {}",
                        event.getId(), maxAttempts, event.getTopic(), event.getPartitionKey(), e.getMessage());
            } else {
                long backoffSeconds = calculateBackoffSeconds(attempts);
                event.setNextAttemptAt(now.plusSeconds(backoffSeconds));
                log.warn("Outbox event {} failed attempt {}/{}, will retry in {}s: {}",
                        event.getId(), attempts, maxAttempts, backoffSeconds, e.getMessage());
            }
            outboxEventRepository.save(event);
        }
    }

    private long calculateBackoffSeconds(int attempt) {
        return (long) Math.min(300, Math.pow(2, attempt));
    }
}
