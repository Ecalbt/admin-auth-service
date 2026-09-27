package com.example.adminauth.repository;

import com.example.adminauth.entity.AdminRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AdminRoleRepository extends JpaRepository<AdminRole, Long> {
    List<AdminRole> findByAdminId(String adminId);
    void deleteByAdminId(String adminId);
}
