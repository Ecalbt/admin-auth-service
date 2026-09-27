package com.example.adminauth.service;

import com.example.adminauth.dto.admin.*;
import com.example.adminauth.security.AdminPrincipal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Service Interface quản lý vòng đời tài khoản Admin và phân quyền Hybrid (Role + Grants).
 * Tất cả các phương thức đều nhận và trả về DTO, che giấu hoàn toàn Entity.
 */
public interface AdminManagementService {

    AdminDetailDto createAdmin(CreateAdminRequest req, AdminPrincipal actor);

    AdminDetailDto updateAdmin(String adminId, UpdateAdminRequest req, AdminPrincipal actor);

    void disableAdmin(String adminId, AdminPrincipal actor);

    void enableAdmin(String adminId, AdminPrincipal actor);

    ResetPasswordResponse resetPassword(String adminId, AdminPrincipal actor);

    AdminDetailDto assignRolesAndPermissions(String adminId, AssignRoleRequest req, AdminPrincipal actor);

    AdminDetailDto getAdminDetail(String adminId);

    Page<AdminSummaryDto> getAllAdmins(Pageable pageable);
}
