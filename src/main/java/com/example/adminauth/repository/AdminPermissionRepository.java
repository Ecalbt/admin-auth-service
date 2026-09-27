package com.example.adminauth.repository;

import com.example.adminauth.entity.AdminPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminPermissionRepository extends JpaRepository<AdminPermission, Long> {
    List<AdminPermission> findByAdminIdAndRevokedAtIsNull(String adminId);
    List<AdminPermission> findByAdminId(String adminId);
    void deleteByAdminId(String adminId);
}
