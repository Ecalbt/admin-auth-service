package com.example.adminauth.controller;

import com.example.adminauth.common.ApiResponse;
import com.example.adminauth.dto.admin.*;
import com.example.adminauth.dto.session.AdminSessionDto;
import com.example.adminauth.security.AdminPrincipal;
import com.example.adminauth.service.AdminManagementService;
import com.example.adminauth.service.SessionManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "Admin Management", description = "Admin Lifecycle & Hybrid Permission Operations")
@RestController
@RequestMapping("/v1/admins")
@RequiredArgsConstructor
public class AdminController {

    private final AdminManagementService adminManagementService;
    private final SessionManagementService sessionManagementService;

    @Operation(summary = "Create new admin account with initial role and optional custom grants")
    @PreAuthorize("@authz.hasPerm('admin:create')")
    @PostMapping
    public ApiResponse<AdminDetailDto> createAdmin(
            @Valid @RequestBody CreateAdminRequest req,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.ok("Admin created successfully", adminManagementService.createAdmin(req, principal));
    }

    @Operation(summary = "List admin accounts with pagination")
    @PreAuthorize("@authz.hasPerm('admin:read')")
    @GetMapping
    public ApiResponse<Page<AdminSummaryDto>> getAllAdmins(@ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(adminManagementService.getAllAdmins(pageable));
    }

    @Operation(summary = "Get admin details including roles and granted scopes")
    @PreAuthorize("@authz.hasPerm('admin:read')")
    @GetMapping("/{id}")
    public ApiResponse<AdminDetailDto> getAdminDetail(@PathVariable String id) {
        return ApiResponse.ok(adminManagementService.getAdminDetail(id));
    }

    @Operation(summary = "Update admin details (email, fullName)")
    @PreAuthorize("@authz.hasPerm('admin:update')")
    @PatchMapping("/{id}")
    public ApiResponse<AdminDetailDto> updateAdmin(
            @PathVariable String id,
            @Valid @RequestBody UpdateAdminRequest req,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.ok("Admin updated successfully", adminManagementService.updateAdmin(id, req, principal));
    }

    @Operation(summary = "Disable admin account (immediately terminates all active sessions)")
    @PreAuthorize("@authz.hasPerm('admin:disable')")
    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disableAdmin(
            @PathVariable String id,
            @AuthenticationPrincipal AdminPrincipal principal) {
        adminManagementService.disableAdmin(id, principal);
        return ApiResponse.ok("Admin disabled successfully and all sessions revoked");
    }

    @Operation(summary = "Enable admin account")
    @PreAuthorize("@authz.hasPerm('admin:enable')")
    @PostMapping("/{id}/enable")
    public ApiResponse<Void> enableAdmin(
            @PathVariable String id,
            @AuthenticationPrincipal AdminPrincipal principal) {
        adminManagementService.enableAdmin(id, principal);
        return ApiResponse.ok("Admin enabled successfully");
    }

    @Operation(summary = "Reset password for an admin (revokes all active sessions)")
    @PreAuthorize("@authz.hasPerm('admin:reset_password')")
    @PostMapping("/{id}/reset-password")
    public ApiResponse<ResetPasswordResponse> resetPassword(
            @PathVariable String id,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.ok("Password reset successfully", adminManagementService.resetPassword(id, principal));
    }

    @Operation(summary = "Assign roles and custom permissions (revokes all sessions to enforce snapshot token)")
    @PreAuthorize("@authz.hasPerm('admin:assign_role')")
    @PutMapping("/{id}/roles")
    public ApiResponse<AdminDetailDto> assignRolesAndPermissions(
            @PathVariable String id,
            @Valid @RequestBody AssignRoleRequest req,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.ok("Roles and permissions updated successfully",
                adminManagementService.assignRolesAndPermissions(id, req, principal));
    }

    @Operation(summary = "Get active sessions of a specific admin")
    @PreAuthorize("@authz.hasPerm('session:read_any')")
    @GetMapping("/{id}/sessions")
    public ApiResponse<List<AdminSessionDto>> getAdminSessions(
            @PathVariable String id,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.ok(sessionManagementService.getAdminSessions(id, principal));
    }

    @Operation(summary = "Kick a specific session of an admin")
    @PreAuthorize("@authz.hasPerm('session:revoke_any')")
    @DeleteMapping("/{id}/sessions/{sid}")
    public ApiResponse<Void> revokeAdminSession(
            @PathVariable String id,
            @PathVariable String sid,
            @AuthenticationPrincipal AdminPrincipal principal) {
        sessionManagementService.revokeSession(id, sid, principal);
        return ApiResponse.ok("Admin session revoked successfully");
    }

    @Operation(summary = "Kick all sessions of an admin")
    @PreAuthorize("@authz.hasPerm('session:revoke_any')")
    @DeleteMapping("/{id}/sessions")
    public ApiResponse<Void> revokeAllAdminSessions(
            @PathVariable String id,
            @AuthenticationPrincipal AdminPrincipal principal) {
        sessionManagementService.revokeAllSessions(id, principal);
        return ApiResponse.ok("All sessions for admin revoked successfully");
    }
}
