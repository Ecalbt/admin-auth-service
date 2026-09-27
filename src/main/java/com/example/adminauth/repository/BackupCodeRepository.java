package com.example.adminauth.repository;

import com.example.adminauth.entity.BackupCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BackupCodeRepository extends JpaRepository<BackupCode, Long> {
    List<BackupCode> findByAdminIdAndUsedAtIsNull(String adminId);
    Optional<BackupCode> findByAdminIdAndCodeHashAndUsedAtIsNull(String adminId, String codeHash);
    void deleteByAdminId(String adminId);
}
