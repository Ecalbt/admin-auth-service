package com.example.adminauth.service;

import com.example.adminauth.dto.admin.*;
import com.example.adminauth.entity.*;
import com.example.adminauth.exception.BusinessException;
import com.example.adminauth.exception.ResourceNotFoundException;
import com.example.adminauth.repository.*;
import com.example.adminauth.security.AdminPrincipal;
import com.example.adminauth.security.jwt.GrantDto;
import com.example.adminauth.security.session.SessionRedisService;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
    @Mock
    private com.example.adminauth.messaging.NotificationService notificationService;
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

    @Test
    @DisplayName("A-02: Creating admin with duplicate username throws BusinessException")
    void testCreateAdminDuplicateUsername() {
        AdminPrincipal superActor = AdminPrincipal.builder()
                .roles(List.of("SUPERADMIN"))
                .build();

        CreateAdminRequest req = new CreateAdminRequest(
                "existing_user", "new@ocb.com.vn", "Name", "SERVICE_ADMIN", null
        );

        when(adminRepository.existsByUsername("existing_user")).thenReturn(true);

        assertThatThrownBy(() -> adminManagementService.createAdmin(req, superActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Username 'existing_user' is already taken");
    }

    @Test
    @DisplayName("A-02: Creating admin with duplicate email throws BusinessException")
    void testCreateAdminDuplicateEmail() {
        AdminPrincipal superActor = AdminPrincipal.builder()
                .roles(List.of("SUPERADMIN"))
                .build();

        CreateAdminRequest req = new CreateAdminRequest(
                "new_user", "taken@ocb.com.vn", "Name", "SERVICE_ADMIN", null
        );

        when(adminRepository.existsByUsername("new_user")).thenReturn(false);
        when(adminRepository.existsByEmail("taken@ocb.com.vn")).thenReturn(true);

        assertThatThrownBy(() -> adminManagementService.createAdmin(req, superActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Email 'taken@ocb.com.vn' is already in use");
    }

    @Test
    @DisplayName("A-05: Non-authorized admin (SERVICE_ADMIN) attempting to create admin is denied")
    void testServiceAdminCannotCreateAdmin() {
        AdminPrincipal svcActor = AdminPrincipal.builder()
                .id("adm-svc")
                .username("svc_user")
                .roles(List.of("SERVICE_ADMIN"))
                .build();

        CreateAdminRequest req = new CreateAdminRequest(
                "sub_user", "sub@ocb.com.vn", "Sub", "SERVICE_ADMIN", null
        );

        when(adminRepository.existsByUsername("sub_user")).thenReturn(false);
        when(adminRepository.existsByEmail("sub@ocb.com.vn")).thenReturn(false);
        when(roleRepository.findByCode("SERVICE_ADMIN")).thenReturn(Optional.of(
                Role.builder().code("SERVICE_ADMIN").tier(3).build()
        ));

        assertThatThrownBy(() -> adminManagementService.createAdmin(req, svcActor))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("You do not have permission to create accounts");
    }

    @Test
    @DisplayName("A-06: Creating admin with non-existent roleCode throws ResourceNotFoundException")
    void testCreateAdminRoleNotFound() {
        AdminPrincipal superActor = AdminPrincipal.builder()
                .roles(List.of("SUPERADMIN"))
                .build();

        CreateAdminRequest req = new CreateAdminRequest(
                "user1", "user1@ocb.com.vn", "User One", "NON_EXISTENT_ROLE", null
        );

        when(adminRepository.existsByUsername("user1")).thenReturn(false);
        when(adminRepository.existsByEmail("user1@ocb.com.vn")).thenReturn(false);
        when(roleRepository.findByCode("NON_EXISTENT_ROLE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adminManagementService.createAdmin(req, superActor))
                .isInstanceOf(com.example.adminauth.exception.ResourceNotFoundException.class)
                .hasMessageContaining("Role not found with code: NON_EXISTENT_ROLE");
    }

    @Test
    @DisplayName("A-11: OPS_ADMIN attempting to disable SUPERADMIN (Tier 1) is blocked by tier guardrail")
    void testOpsCannotDisableSuperAdmin() {
        AdminPrincipal opsActor = AdminPrincipal.builder()
                .id("adm-ops")
                .username("ops_admin")
                .roles(List.of("OPERATIONS_ADMIN"))
                .build();

        Admin superTarget = Admin.builder()
                .id("adm-super-target")
                .username("super_target")
                .build();

        when(adminRepository.findById("adm-super-target")).thenReturn(Optional.of(superTarget));
        when(adminRoleRepository.findByAdminId("adm-super-target")).thenReturn(List.of(
                AdminRole.builder().role(Role.builder().code("SUPERADMIN").tier(1).build()).build()
        ));

        assertThatThrownBy(() -> adminManagementService.disableAdmin("adm-super-target", opsActor))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("OPERATIONS_ADMIN is not authorized to disable accounts of tier 1");
    }

    @Test
    @DisplayName("A-13: Enabling an admin resets failed login attempts and unlocks account")
    void testEnableAdminSuccess() {
        AdminPrincipal superActor = AdminPrincipal.builder()
                .id("adm-super")
                .username("superadmin")
                .roles(List.of("SUPERADMIN"))
                .build();

        Admin target = Admin.builder()
                .id("adm-target-enable")
                .username("disabled_target")
                .status(AdminStatus.DISABLED)
                .failedLoginAttempts(5)
                .build();

        when(adminRepository.findById("adm-target-enable")).thenReturn(Optional.of(target));

        adminManagementService.enableAdmin("adm-target-enable", superActor);

        assertThat(target.getStatus()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(target.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(target.getLockedUntil()).isNull();
        verify(adminRepository).save(target);
    }

    @Test
    @DisplayName("A-14: Reset password generates temporary password and revokes all sessions")
    void testResetPasswordGeneratesTempPasswordAndRevokesSessions() {
        AdminPrincipal opsActor = AdminPrincipal.builder()
                .id("adm-ops")
                .username("ops_admin")
                .roles(List.of("OPERATIONS_ADMIN"))
                .build();

        Admin svcTarget = Admin.builder()
                .id("adm-svc-target")
                .username("svc_target")
                .status(AdminStatus.ACTIVE)
                .build();

        when(adminRepository.findById("adm-svc-target")).thenReturn(Optional.of(svcTarget));
        when(adminRoleRepository.findByAdminId("adm-svc-target")).thenReturn(List.of(
                AdminRole.builder().role(Role.builder().code("SERVICE_ADMIN").tier(3).build()).build()
        ));
        when(passwordEncoder.encode(any())).thenReturn("new_temp_hash");

        var response = adminManagementService.resetPassword("adm-svc-target", opsActor);

        assertThat(response).isNotNull();
        assertThat(response.temporaryPassword()).hasSize(14);
        assertThat(svcTarget.getMustChangePassword()).isTrue();
        assertThat(svcTarget.getFailedLoginAttempts()).isEqualTo(0);

        verify(sessionRedisService).revokeAllSessionsForAdmin("adm-svc-target");
        verify(refreshTokenRepository).revokeAllForAdmin(eq("adm-svc-target"), any());
    }

    @Test
    @DisplayName("A-07: Create admin with hybrid customGrants applies explicit permissions plus auth:self")
    void testCreateAdminWithCustomGrants() {
        AdminPrincipal superActor = AdminPrincipal.builder()
                .id("adm-super")
                .username("superadmin")
                .roles(List.of("SUPERADMIN"))
                .build();

        List<GrantRequestDto> customGrants = List.of(
                new GrantRequestDto("config:write", List.of("system-params-api")),
                new GrantRequestDto("config:read", null)
        );

        CreateAdminRequest req = new CreateAdminRequest(
                "custom_maker", "maker@ocb.com.vn", "Custom Maker", "SERVICE_ADMIN", customGrants
        );

        Role serviceRole = Role.builder().code("SERVICE_ADMIN").tier(3).presetPermissions(List.of()).build();
        when(adminRepository.existsByUsername("custom_maker")).thenReturn(false);
        when(adminRepository.existsByEmail("maker@ocb.com.vn")).thenReturn(false);
        when(roleRepository.findByCode("SERVICE_ADMIN")).thenReturn(Optional.of(serviceRole));
        when(passwordEncoder.encode(any())).thenReturn("hashed_pw");
        when(adminRepository.save(any(Admin.class))).thenAnswer(i -> i.getArgument(0));
        when(permissionRepository.findByCode("config:write")).thenReturn(Optional.of(Permission.builder().code("config:write").build()));
        when(permissionRepository.findByCode("config:read")).thenReturn(Optional.of(Permission.builder().code("config:read").build()));
        when(permissionRepository.findByCode("auth:self")).thenReturn(Optional.of(Permission.builder().code("auth:self").build()));
        when(adminRepository.findById(any())).thenReturn(Optional.of(Admin.builder().id("adm-new-cg").username("custom_maker").build()));
        when(adminRoleRepository.findByAdminId(any())).thenReturn(List.of(AdminRole.builder().role(serviceRole).build()));
        when(adminMapper.toDetailDto(any(), any(), any())).thenReturn(
                AdminDetailDto.builder().id("adm-new-cg").username("custom_maker").build()
        );

        var resp = adminManagementService.createAdmin(req, superActor);

        assertThat(resp).isNotNull();
        verify(adminPermissionRepository, atLeast(3)).save(any(AdminPermission.class));
    }

    @Test
    @DisplayName("A-08: Get admin detail and get all admins")
    void testGetAdminDetailAndList() {
        Admin admin = Admin.builder().id("adm-detail").username("detail_user").build();
        when(adminRepository.findById("adm-detail")).thenReturn(Optional.of(admin));
        when(adminRoleRepository.findByAdminId("adm-detail")).thenReturn(List.of(
                AdminRole.builder().role(Role.builder().code("SERVICE_ADMIN").build()).build()
        ));
        when(adminMapper.toDetailDto(any(), any(), any())).thenReturn(
                AdminDetailDto.builder().id("adm-detail").username("detail_user").build()
        );

        AdminDetailDto detail = adminManagementService.getAdminDetail("adm-detail");
        assertThat(detail).isNotNull();
        assertThat(detail.username()).isEqualTo("detail_user");

        when(adminRepository.findById("unknown-id")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adminManagementService.getAdminDetail("unknown-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Admin not found with id: unknown-id");
    }

    @Test
    @DisplayName("A-08b: Get admin detail for SUPERADMIN returns wildcard grant")
    void testSuperAdminDetailReturnsWildcard() {
        Admin superAdmin = Admin.builder().id("adm-super").username("superadmin").build();
        when(adminRepository.findById("adm-super")).thenReturn(Optional.of(superAdmin));
        when(adminRoleRepository.findByAdminId("adm-super")).thenReturn(List.of(
                AdminRole.builder().role(Role.builder().code("SUPERADMIN").tier(1).build()).build()
        ));
        when(adminMapper.toDetailDto(eq(superAdmin), eq(List.of("SUPERADMIN")), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<GrantDto> grants = inv.getArgument(2);
            return AdminDetailDto.builder()
                    .id("adm-super")
                    .username("superadmin")
                    .roles(List.of("SUPERADMIN"))
                    .permissions(grants)
                    .build();
        });

        AdminDetailDto detail = adminManagementService.getAdminDetail("adm-super");
        assertThat(detail).isNotNull();
        assertThat(detail.permissions()).hasSize(1);
        assertThat(detail.permissions().get(0).perm()).isEqualTo("*");
        assertThat(detail.permissions().get(0).scope()).containsExactly("*");
    }

    @Test
    @DisplayName("A-09: Update admin profile and fail if email is already taken")
    void testUpdateAdminProfile() {
        AdminPrincipal superActor = AdminPrincipal.builder()
                .id("adm-super")
                .username("superadmin")
                .roles(List.of("SUPERADMIN"))
                .build();

        Admin target = Admin.builder()
                .id("adm-upd")
                .username("upd_user")
                .email("old@ocb.com.vn")
                .fullName("Old Name")
                .build();

        when(adminRepository.findById("adm-upd")).thenReturn(Optional.of(target));
        when(adminRoleRepository.findByAdminId("adm-upd")).thenReturn(List.of(
                AdminRole.builder().role(Role.builder().code("SERVICE_ADMIN").tier(3).build()).build()
        ));
        when(adminRepository.existsByEmail("new@ocb.com.vn")).thenReturn(false);
        when(adminMapper.toDetailDto(any(), any(), any())).thenReturn(
                AdminDetailDto.builder().id("adm-upd").email("new@ocb.com.vn").fullName("New Name").build()
        );

        UpdateAdminRequest req = new UpdateAdminRequest("New Name", "new@ocb.com.vn");
        AdminDetailDto updated = adminManagementService.updateAdmin("adm-upd", req, superActor);

        assertThat(updated.email()).isEqualTo("new@ocb.com.vn");
        assertThat(target.getFullName()).isEqualTo("New Name");

        when(adminRepository.existsByEmail("conflict@ocb.com.vn")).thenReturn(true);
        UpdateAdminRequest conflictReq = new UpdateAdminRequest("New Name", "conflict@ocb.com.vn");
        assertThatThrownBy(() -> adminManagementService.updateAdmin("adm-upd", conflictReq, superActor))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already taken");
    }

    @Test
    @DisplayName("A-15: Superadmin PUT roles replaces roles and permissions completely and revokes all sessions")
    void testAssignRolesAndPermissionsSuccess() {
        AdminPrincipal superActor = AdminPrincipal.builder()
                .id("adm-super")
                .username("superadmin")
                .roles(List.of("SUPERADMIN"))
                .build();

        Admin target = Admin.builder().id("adm-assign").username("target_user").build();
        Role opsRole = Role.builder().code("OPERATIONS_ADMIN").tier(2).presetPermissions(List.of()).build();

        when(adminRepository.findById("adm-assign")).thenReturn(Optional.of(target));
        when(adminRoleRepository.findByAdminId("adm-assign")).thenReturn(List.of(
                AdminRole.builder().role(Role.builder().code("SERVICE_ADMIN").tier(3).build()).build()
        ));
        when(roleRepository.findByCode("OPERATIONS_ADMIN")).thenReturn(Optional.of(opsRole));
        when(adminMapper.toDetailDto(any(), any(), any())).thenReturn(
                AdminDetailDto.builder().id("adm-assign").username("target_user").build()
        );

        AssignRoleRequest req = new AssignRoleRequest(List.of("OPERATIONS_ADMIN"), null);
        AdminDetailDto result = adminManagementService.assignRolesAndPermissions("adm-assign", req, superActor);

        assertThat(result).isNotNull();
        verify(adminRoleRepository).deleteByAdminId("adm-assign");
        verify(adminPermissionRepository).deleteByAdminId("adm-assign");
        verify(sessionRedisService).revokeAllSessionsForAdmin("adm-assign");
        verify(refreshTokenRepository).revokeAllForAdmin(eq("adm-assign"), any());
    }

    @Test
    @DisplayName("A-16: OPS_ADMIN cannot assign role of Tier 2 or Tier 1")
    void testOpsAdminCannotAssignTier2Role() {
        AdminPrincipal opsActor = AdminPrincipal.builder()
                .id("adm-ops")
                .username("ops_admin")
                .roles(List.of("OPERATIONS_ADMIN"))
                .build();

        Admin target = Admin.builder().id("adm-tier3").username("tier3_user").build();
        Role opsRole = Role.builder().code("OPERATIONS_ADMIN").tier(2).build();

        when(adminRepository.findById("adm-tier3")).thenReturn(Optional.of(target));
        when(adminRoleRepository.findByAdminId("adm-tier3")).thenReturn(List.of(
                AdminRole.builder().role(Role.builder().code("SERVICE_ADMIN").tier(3).build()).build()
        ));
        when(roleRepository.findByCode("OPERATIONS_ADMIN")).thenReturn(Optional.of(opsRole));

        AssignRoleRequest req = new AssignRoleRequest(List.of("OPERATIONS_ADMIN"), null);

        assertThatThrownBy(() -> adminManagementService.assignRolesAndPermissions("adm-tier3", req, opsActor))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("OPERATIONS_ADMIN is not authorized to assign role OPERATIONS_ADMIN");
    }
}
