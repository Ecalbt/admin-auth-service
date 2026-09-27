package com.example.adminauth.repository;

import com.example.adminauth.entity.PasswordHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {
    @Query("SELECT p FROM PasswordHistory p WHERE p.admin.id = :adminId ORDER BY p.changedAt DESC")
    List<PasswordHistory> findRecentHistory(@Param("adminId") String adminId, Pageable pageable);
}
