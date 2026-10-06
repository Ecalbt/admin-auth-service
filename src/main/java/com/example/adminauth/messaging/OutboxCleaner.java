package com.example.adminauth.messaging;

import com.example.adminauth.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Dọn dẹp định kỳ các bản ghi OutboxEvent có trạng thái PUBLISHED đã quá hạn lưu trữ.
 * Không xóa các bản ghi FAILED để phục vụ điều tra/replay thủ công.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleaner {

    private final OutboxEventRepository outboxEventRepository;
    private final MeterRegistry meterRegistry;

    @Value("${app.outbox.cleaner.retention-days:14}")
    private int retentionDays;

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupPublishedEvents() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("Starting outbox cleanup for events published before {}", cutoff);

        int totalDeleted = 0;
        int batchSize = 1000;
        int deleted;

        do {
            deleted = outboxEventRepository.deletePublishedOlderThan(cutoff, batchSize);
            totalDeleted += deleted;
        } while (deleted == batchSize);

        if (totalDeleted > 0) {
            meterRegistry.counter("outbox.cleaned").increment(totalDeleted);
            log.info("Outbox cleanup completed: deleted {} PUBLISHED events older than {} days", totalDeleted, retentionDays);
        } else {
            log.debug("Outbox cleanup completed: no eligible PUBLISHED events to delete");
        }
    }
}
