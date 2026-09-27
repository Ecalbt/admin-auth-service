package com.example.adminauth.service;

import com.example.adminauth.dto.admin.CreateAdminRequest;
import com.example.adminauth.entity.Admin;
import com.example.adminauth.entity.AdminStatus;
import com.example.adminauth.entity.Role;
import com.example.adminauth.exception.BusinessException;
import com.example.adminauth.repository.*;
import com.example.adminauth.security.AdminPrincipal;
import com.example.adminauth.security.session.SessionRedisService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminManagementServiceTest {

    @Mock
    private AdminRepository adminRepository;
    @Mock
    private RoleRepository roleRepository;
    @Mock
    private PermissionRepository permissionRepository;
    @Mock
    private AdminRoleRepository adminRoleRepository;
    @Mock
    private AdminPermissionRepository adminPermissionRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private SessionRedisService sessionRedisService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AuditService auditService;
    @Mock
    private com.example.adminauth.mapper.AdminMapper adminMapper;
    @Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private com.example.adminauth.service.impl.AdminManagementServiceImpl adminManagementService;

    @Test
    @DisplayName("Tier guardrail: OPERATIONS_ADMIN cannot create SUPERADMIN (Tier 1)")
    void testOperationsAdminCannotCreateSuperAdmin() {
        AdminPrincipal opsActor = AdminPrincipal.builder()
                .id("adm-ops")
                .username("ops_admin")
                .roles(List.of("OPERATIONS_ADMIN"))
                .build();

        CreateAdminRequest req = new CreateAdminRequest(
                "new_super", "super2@ocb.com.vn", "Super Two", "SUPERADMIN", null
        );

        when(adminRepository.existsByUsername("new_super")).thenReturn(false);
        when(adminRepository.existsByEmail("super2@ocb.com.vn")).thenReturn(false);
        when(roleRepository.findByCode("SUPERADMIN")).thenReturn(Optional.of(
                Role.builder().code("SUPERADMIN").tier(1).build()
        ));

        assertThatThrownBy(() -> adminManagementService.createAdmin(req, opsActor))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("OPERATIONS_ADMIN is not authorized");
    }

    @Test
    @DisplayName("SuperAdmin can create any role (Tier 1 can create Tier 2, Tier 3)")
    void testSuperAdminCanCreateAny() {
        AdminPrincipal superActor = AdminPrincipal.builder()
                .id("adm-super")
                .username("superadmin")
                .roles(List.of("SUPERADMIN"))
                .build();

        CreateAdminRequest req = new CreateAdminRequest(
                "new_service_admin", "svc@ocb.com.vn", "Service Admin", "SERVICE_ADMIN", null
        );

        Role serviceRole = Role.builder().code("SERVICE_ADMIN").tier(3).presetPermissions(List.of()).build();

        when(adminRepository.existsByUsername("new_service_admin")).thenReturn(false);
        when(adminRepository.existsByEmail("svc@ocb.com.vn")).thenReturn(false);
        when(roleRepository.findByCode("SERVICE_ADMIN")).thenReturn(Optional.of(serviceRole));
        when(passwordEncoder.encode(any())).thenReturn("hashed_pw");
        when(adminRepository.save(any(Admin.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(adminRepository.findById(any())).thenReturn(Optional.of(
                Admin.builder().id("new-id").username("new_service_admin").status(AdminStatus.PENDING_ACTIVATION).build()
        ));
        when(adminMapper.toDetailDto(any(), any(), any())).thenReturn(
                com.example.adminauth.dto.admin.AdminDetailDto.builder().id("new-id").username("new_service_admin").build()
        );

        var response = adminManagementService.createAdmin(req, superActor);
        assertThat(response).isNotNull();
        assertThat(response.temporaryPassword()).isNotBlank();
        assertThat(response.adminDetail().username()).isEqualTo("new_service_admin");
        verify(adminRoleRepository).save(any());
    }

    @Test
    @DisplayName("Admin cannot disable their own account")
    void testCannotDisableSelf() {
        AdminPrincipal actor = AdminPrincipal.builder()
                .id("adm-self")
                .username("my_user")
                .roles(List.of("SUPERADMIN"))
                .build();

        assertThatThrownBy(() -> adminManagementService.disableAdmin("adm-self", actor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot disable your own account");
    }

    @Test
    @DisplayName("Disabling admin revokes all active sessions immediately")
    void testDisableAdminRevokesSessions() {
        AdminPrincipal superActor = AdminPrincipal.builder()
                .id("adm-super")
                .username("superadmin")
                .roles(List.of("SUPERADMIN"))
                .build();

        Admin target = Admin.builder()
                .id("adm-target")
                .username("bad_actor")
                .status(AdminStatus.ACTIVE)
                .build();

        when(adminRepository.findById("adm-target")).thenReturn(Optional.of(target));
        when(adminRoleRepository.findByAdminId("adm-target")).thenReturn(List.of());

        adminManagementService.disableAdmin("adm-target", superActor);

        assertThat(target.getStatus()).isEqualTo(AdminStatus.DISABLED);
        verify(sessionRedisService).revokeAllSessionsForAdmin("adm-target");
        verify(refreshTokenRepository).revokeAllForAdmin(eq("adm-target"), any());
    }
}
