package com.example.adminauth.service.impl;

import com.example.adminauth.dto.admin.*;
import com.example.adminauth.entity.*;
import com.example.adminauth.exception.BusinessException;
import com.example.adminauth.exception.ResourceNotFoundException;
import com.example.adminauth.mapper.AdminMapper;
import com.example.adminauth.repository.*;
import com.example.adminauth.security.AdminPrincipal;
import com.example.adminauth.security.jwt.GrantDto;
import com.example.adminauth.security.session.SessionRedisService;
import com.example.adminauth.service.AdminManagementService;
import com.example.adminauth.service.AuditService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminManagementServiceImpl implements AdminManagementService {

    private final AdminRepository adminRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final AdminRoleRepository adminRoleRepository;
    private final AdminPermissionRepository adminPermissionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SessionRedisService sessionRedisService;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final AdminMapper adminMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public AdminDetailDto createAdmin(CreateAdminRequest req, AdminPrincipal actor) {
        if (adminRepository.existsByUsername(req.username())) {
            throw new BusinessException("Username '" + req.username() + "' is already taken");
        }
        if (adminRepository.existsByEmail(req.email())) {
            throw new BusinessException("Email '" + req.email() + "' is already in use");
        }

        Role targetRole = roleRepository.findByCode(req.roleCode())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found with code: " + req.roleCode()));

        // Tier Guardrail enforcement
        enforceTierGuardrail(actor, targetRole.getTier(), "create");

        String rawPassword = (req.initialPassword() != null && !req.initialPassword().isBlank())
                ? req.initialPassword()
                : generateSecureInitialPassword();

        String adminId = UUID.randomUUID().toString();
        Admin admin = Admin.builder()
                .id(adminId)
                .username(req.username())
                .email(req.email())
                .fullName(req.fullName())
                .passwordHash(passwordEncoder.encode(rawPassword))
                .status(AdminStatus.PENDING_ACTIVATION)
                .mustChangePassword(true)
                .createdBy(actor.getUsername())
                .build();

        admin = adminRepository.save(admin);

        // Assign Role
        AdminRole adminRole = AdminRole.builder()
                .admin(admin)
                .role(targetRole)
                .assignedBy(actor.getUsername())
                .build();
        adminRoleRepository.save(adminRole);

        // Assign Permissions (Hybrid: Preload Preset + Apply Custom Grants)
        applyPermissionsToAdmin(admin, List.of(targetRole), req.customGrants(), actor.getUsername());

        auditService.recordEvent(
                actor.getUsername(), "ACCOUNT_CREATED", admin.getId(),
                null, Map.of("username", admin.getUsername(), "role", targetRole.getCode()),
                null, null, null
        );

        return getAdminDetail(admin.getId());
    }

    @Override
    @Transactional
    public AdminDetailDto updateAdmin(String adminId, UpdateAdminRequest req, AdminPrincipal actor) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found with id: " + adminId));

        int targetTier = getHighestTier(admin);
        enforceTierGuardrail(actor, targetTier, "update");

        if (req.email() != null && !req.email().equalsIgnoreCase(admin.getEmail())) {
            if (adminRepository.existsByEmail(req.email())) {
                throw new BusinessException("Email '" + req.email() + "' is already taken");
            }
            admin.setEmail(req.email());
        }

        if (req.fullName() != null) {
            admin.setFullName(req.fullName());
        }

        admin.setUpdatedBy(actor.getUsername());
        adminRepository.save(admin);

        auditService.recordEvent(actor.getUsername(), "ACCOUNT_UPDATED", admin.getId(), null, req, null, null, null);
        return getAdminDetail(admin.getId());
    }

    @Override
    @Transactional
    public void disableAdmin(String adminId, AdminPrincipal actor) {
        if (adminId.equals(actor.getId())) {
            throw new BusinessException("You cannot disable your own account");
        }

        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found with id: " + adminId));

        int targetTier = getHighestTier(admin);
        enforceTierGuardrail(actor, targetTier, "disable");

        admin.setStatus(AdminStatus.DISABLED);
        admin.setUpdatedBy(actor.getUsername());
        adminRepository.save(admin);

        // Terminate all sessions and revoke refresh tokens immediately
        sessionRedisService.revokeAllSessionsForAdmin(adminId);
        refreshTokenRepository.revokeAllForAdmin(adminId, LocalDateTime.now());

        auditService.recordEvent(actor.getUsername(), "ACCOUNT_DISABLED", admin.getId(), null, null, null, null, null);
        log.info("Admin '{}' has been disabled by '{}'", admin.getUsername(), actor.getUsername());
    }

    @Override
    @Transactional
    public void enableAdmin(String adminId, AdminPrincipal actor) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found with id: " + adminId));

        int targetTier = getHighestTier(admin);
        enforceTierGuardrail(actor, targetTier, "enable");

        admin.setStatus(AdminStatus.ACTIVE);
        admin.setFailedLoginAttempts(0);
        admin.setLockedUntil(null);
        admin.setUpdatedBy(actor.getUsername());
        adminRepository.save(admin);

        auditService.recordEvent(actor.getUsername(), "ACCOUNT_ENABLED", admin.getId(), null, null, null, null, null);
        log.info("Admin '{}' has been enabled by '{}'", admin.getUsername(), actor.getUsername());
    }

    @Override
    @Transactional
    public ResetPasswordResponse resetPassword(String adminId, AdminPrincipal actor) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found with id: " + adminId));

        int targetTier = getHighestTier(admin);
        enforceTierGuardrail(actor, targetTier, "reset password for");

        String temporaryPassword = generateSecureInitialPassword();
        admin.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        admin.setMustChangePassword(true);
        admin.setFailedLoginAttempts(0);
        admin.setLockedUntil(null);
        admin.setUpdatedBy(actor.getUsername());
        adminRepository.save(admin);

        // Terminate all current sessions for security
        sessionRedisService.revokeAllSessionsForAdmin(adminId);
        refreshTokenRepository.revokeAllForAdmin(adminId, LocalDateTime.now());

        auditService.recordEvent(actor.getUsername(), "PASSWORD_RESET", admin.getId(), null, null, null, null, null);
        log.info("Password for admin '{}' reset by '{}'", admin.getUsername(), actor.getUsername());

        return new ResetPasswordResponse(temporaryPassword);
    }

    @Override
    @Transactional
    public AdminDetailDto assignRolesAndPermissions(String adminId, AssignRoleRequest req, AdminPrincipal actor) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found with id: " + adminId));

        int targetTier = getHighestTier(admin);
        enforceTierGuardrail(actor, targetTier, "modify roles of");

        List<Role> targetRoles = new ArrayList<>();
        for (String roleCode : req.roleCodes()) {
            Role role = roleRepository.findByCode(roleCode)
                    .orElseThrow(() -> new ResourceNotFoundException("Role not found with code: " + roleCode));
            enforceTierGuardrail(actor, role.getTier(), "assign role " + roleCode + " to");
            targetRoles.add(role);
        }

        // Replace Roles
        adminRoleRepository.deleteByAdminId(adminId);
        for (Role role : targetRoles) {
            AdminRole ar = AdminRole.builder()
                    .admin(admin)
                    .role(role)
                    .assignedBy(actor.getUsername())
                    .build();
            adminRoleRepository.save(ar);
        }

        // Replace Permissions (Hybrid: Preload Preset + Custom Grants)
        adminPermissionRepository.deleteByAdminId(adminId);
        applyPermissionsToAdmin(admin, targetRoles, req.customGrants(), actor.getUsername());

        // Snapshot token revocation rule: Must revoke all active sessions so user logs in with new permissions
        sessionRedisService.revokeAllSessionsForAdmin(adminId);
        refreshTokenRepository.revokeAllForAdmin(adminId, LocalDateTime.now());

        auditService.recordEvent(
                actor.getUsername(), "ROLE_ASSIGNED", admin.getId(),
                null, Map.of("roles", req.roleCodes()), null, null, null
        );

        return getAdminDetail(adminId);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminDetailDto getAdminDetail(String adminId) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found with id: " + adminId));

        List<String> roleCodes = adminRoleRepository.findByAdminId(adminId).stream()
                .map(ar -> ar.getRole().getCode())
                .toList();

        List<GrantDto> grantDtos = adminMapper.toGrantDtoList(
                adminPermissionRepository.findByAdminIdAndRevokedAtIsNull(adminId)
        );

        return adminMapper.toDetailDto(admin, roleCodes, grantDtos);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminSummaryDto> getAllAdmins(Pageable pageable) {
        return adminRepository.findAll(pageable).map(admin -> {
            List<String> roles = adminRoleRepository.findByAdminId(admin.getId()).stream()
                    .map(ar -> ar.getRole().getCode())
                    .toList();
            return adminMapper.toSummaryDto(admin, roles);
        });
    }

    private void applyPermissionsToAdmin(Admin admin, List<Role> roles, List<GrantRequestDto> customGrants, String actorUsername) {
        Map<String, Set<String>> grantMap = new HashMap<>();

        if (customGrants != null && !customGrants.isEmpty()) {
            // Mode 1: Explicit Custom Grants
            // If customGrants is specified, apply ONLY the requested permissions and scopes
            for (GrantRequestDto cg : customGrants) {
                Set<String> scopes = new HashSet<>();
                if (cg.scopes() != null && !cg.scopes().isEmpty()) {
                    scopes.addAll(cg.scopes());
                } else {
                    scopes.add("*");
                }
                grantMap.put(cg.permissionCode(), scopes);
            }
            // Always ensure 'auth:self' is granted so the admin can manage their own profile, password, MFA, sessions
            grantMap.computeIfAbsent("auth:self", k -> new HashSet<>()).add("*");
        } else {
            // Mode 2: Role Preset Defaults
            // When no customGrants are supplied, load all preset permissions from assigned roles
            for (Role r : roles) {
                if (r.getPresetPermissions() != null) {
                    for (Permission p : r.getPresetPermissions()) {
                        grantMap.computeIfAbsent(p.getCode(), k -> new HashSet<>()).add("*");
                    }
                }
            }
        }

        // Persist to admin_permissions
        for (Map.Entry<String, Set<String>> entry : grantMap.entrySet()) {
            permissionRepository.findByCode(entry.getKey()).ifPresent(p -> {
                String scopeJson;
                try {
                    scopeJson = objectMapper.writeValueAsString(entry.getValue());
                } catch (JsonProcessingException e) {
                    scopeJson = "[\"*\"]";
                }

                AdminPermission ap = AdminPermission.builder()
                        .admin(admin)
                        .permission(p)
                        .scope(scopeJson)
                        .grantedBy(actorUsername)
                        .build();
                adminPermissionRepository.save(ap);
            });
        }
    }

    private int getHighestTier(Admin admin) {
        List<AdminRole> roles = adminRoleRepository.findByAdminId(admin.getId());
        return roles.stream()
                .mapToInt(ar -> ar.getRole().getTier())
                .min()
                .orElse(99);
    }

    private void enforceTierGuardrail(AdminPrincipal actor, int targetTier, String operation) {
        if (actor.getRoles() != null && actor.getRoles().contains("SUPERADMIN")) {
            return;
        }

        if (actor.getRoles() != null && actor.getRoles().contains("OPERATIONS_ADMIN")) {
            if (targetTier <= 2) {
                throw new AccessDeniedException("OPERATIONS_ADMIN is not authorized to " + operation + " accounts of tier " + targetTier);
            }
            return;
        }

        throw new AccessDeniedException("You do not have permission to " + operation + " accounts");
    }

    private String generateSecureInitialPassword() {
        String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
        String lower = "abcdefghijkmnpqrstuvwxyz";
        String digits = "23456789";
        String special = "@#$%&*";
        String all = upper + lower + digits + special;

        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        sb.append(upper.charAt(random.nextInt(upper.length())));
        sb.append(lower.charAt(random.nextInt(lower.length())));
        sb.append(digits.charAt(random.nextInt(digits.length())));
        sb.append(special.charAt(random.nextInt(special.length())));

        for (int i = 4; i < 14; i++) {
            sb.append(all.charAt(random.nextInt(all.length())));
        }
        return sb.toString();
    }
}
