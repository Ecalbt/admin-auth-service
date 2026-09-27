package com.example.adminauth.repository;

import com.example.adminauth.entity.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long>, JpaSpecificationExecutor<AuditEvent> {
    Page<AuditEvent> findByActorIdOrderByCreatedAtDesc(String actorId, Pageable pageable);
    Page<AuditEvent> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);
    Page<AuditEvent> findByCreatedAtBetweenOrderByCreatedAtDesc(LocalDateTime start, LocalDateTime end, Pageable pageable);
}
