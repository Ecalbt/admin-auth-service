package com.example.adminauth.repository;

import com.example.adminauth.entity.OutboxEvent;
import com.example.adminauth.entity.OutboxStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    @Query(value = "SELECT * FROM outbox_events " +
            "WHERE status = 'PENDING' AND (next_attempt_at IS NULL OR next_attempt_at <= :now) " +
            "ORDER BY created_at ASC " +
            "LIMIT :limit " +
            "FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findPendingBatchForUpdateSkipLocked(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Modifying
    @Query(value = "DELETE FROM outbox_events WHERE id IN (" +
            "SELECT id FROM outbox_events " +
            "WHERE status = 'PUBLISHED' AND published_at < :cutoff " +
            "LIMIT :limit)", nativeQuery = true)
    int deletePublishedOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    long countByStatus(OutboxStatus status);

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxStatus status);
}
