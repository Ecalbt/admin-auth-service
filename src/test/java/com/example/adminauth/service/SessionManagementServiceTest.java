package com.example.adminauth.service;

import com.example.adminauth.security.jwt.GrantDto;
import com.example.adminauth.dto.session.AdminSessionDto;
import com.example.adminauth.entity.Admin;
import com.example.adminauth.entity.AdminRole;
import com.example.adminauth.entity.Role;
import com.example.adminauth.repository.AdminRepository;
import com.example.adminauth.repository.AdminRoleRepository;
import com.example.adminauth.repository.RefreshTokenRepository;
import com.example.adminauth.security.AdminPrincipal;
import com.example.adminauth.security.session.SessionRedisService;
import com.example.adminauth.service.impl.SessionManagementServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionManagementServiceTest {

    @Mock
    private SessionRedisService sessionRedisService;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private AdminRepository adminRepository;

    @Mock
    private AdminRoleRepository adminRoleRepository;

    @Mock
    private com.example.adminauth.messaging.NotificationService notificationService;

    @InjectMocks
    private SessionManagementServiceImpl sessionManagementService;

    @Test
    @DisplayName("OPS_ADMIN cannot revoke session of SUPERADMIN (Tier 1) - GAP-02")
    void testOpsAdminCannotRevokeSuperAdminSession() {
        AdminPrincipal opsActor = AdminPrincipal.builder()
                .id("ops-id")
                .username("ops_admin")
                .roles(List.of("OPERATIONS_ADMIN"))
                .permissions(List.of(new GrantDto("session:revoke_any", List.of("*"))))
                .build();

        Admin superAdmin = Admin.builder().id("super-id").username("superadmin").build();
        Role superRole = Role.builder().code("SUPERADMIN").tier(1).build();

        when(adminRepository.findById("super-id")).thenReturn(Optional.of(superAdmin));
        when(adminRoleRepository.findByAdminId("super-id")).thenReturn(List.of(
                AdminRole.builder().role(superRole).build()
        ));

        assertThatThrownBy(() -> sessionManagementService.revokeSession("super-id", "sess-123", opsActor))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("OPERATIONS_ADMIN is not authorized to manage sessions for tier 1");

        verify(sessionRedisService, never()).revokeSession(any());
    }

    @Test
    @DisplayName("OPS_ADMIN can revoke session of SERVICE_ADMIN (Tier 3)")
    void testOpsAdminCanRevokeServiceAdminSession() {
        AdminPrincipal opsActor = AdminPrincipal.builder()
                .id("ops-id")
                .username("ops_admin")
                .roles(List.of("OPERATIONS_ADMIN"))
                .permissions(List.of(new GrantDto("session:revoke_any", List.of("*"))))
                .build();

        Admin svcAdmin = Admin.builder().id("svc-id").username("svc_maker").build();
        Role svcRole = Role.builder().code("SERVICE_ADMIN").tier(3).build();

        when(adminRepository.findById("svc-id")).thenReturn(Optional.of(svcAdmin));
        when(adminRoleRepository.findByAdminId("svc-id")).thenReturn(List.of(
                AdminRole.builder().role(svcRole).build()
        ));

        sessionManagementService.revokeSession("svc-id", "sess-svc-1", opsActor);

        verify(sessionRedisService).revokeSession("sess-svc-1");
        verify(refreshTokenRepository).revokeSession(eq("sess-svc-1"), any());
    }

    @Test
    @DisplayName("Admin can always view and revoke their own sessions without tier check")
    void testAdminCanManageOwnSessions() {
        AdminPrincipal actor = AdminPrincipal.builder()
                .id("my-id")
                .username("my_user")
                .roles(List.of("SERVICE_ADMIN"))
                .permissions(List.of())
                .build();

        List<AdminSessionDto> sessions = List.of(
                new AdminSessionDto("sess-mine", "my-id", "my_user", "127.0.0.1", "Agent",
                        Instant.now(), Instant.now(), Instant.now().plusSeconds(1800))
        );
        when(sessionRedisService.getSessionsForAdmin("my-id")).thenReturn(sessions);

        List<AdminSessionDto> result = sessionManagementService.getMySessions(actor);
        assertThat(result).hasSize(1);
        verify(adminRepository, never()).findById(any());
    }

    @Test
    @DisplayName("S-03 & S-04: User can revoke their own session")
    void testRevokeOwnSession() {
        AdminPrincipal actor = AdminPrincipal.builder()
                .id("my-id")
                .username("my_user")
                .roles(List.of("SERVICE_ADMIN"))
                .build();

        sessionManagementService.revokeSession("my-id", "sess-mine-1", actor);

        verify(sessionRedisService).revokeSession("sess-mine-1");
        verify(refreshTokenRepository).revokeSession(eq("sess-mine-1"), any());
        verify(auditService).recordEvent(eq("my_user"), eq("SESSION_REVOKED"), eq("my-id"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("S-05: User can revoke all their own sessions")
    void testRevokeAllOwnSessions() {
        AdminPrincipal actor = AdminPrincipal.builder()
                .id("my-id")
                .username("my_user")
                .roles(List.of("SERVICE_ADMIN"))
                .build();

        sessionManagementService.revokeAllSessions("my-id", actor);

        verify(sessionRedisService).revokeAllSessionsForAdmin("my-id");
        verify(refreshTokenRepository).revokeAllForAdmin(eq("my-id"), any());
        verify(auditService).recordEvent(eq("my_user"), eq("SESSION_REVOKED_ALL"), eq("my-id"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("S-06: SUPERADMIN can view and revoke sessions of any admin")
    void testSuperAdminCanViewAndRevokeAnySession() {
        AdminPrincipal superActor = AdminPrincipal.builder()
                .id("super-id")
                .username("superadmin")
                .roles(List.of("SUPERADMIN"))
                .build();

        List<AdminSessionDto> sessions = List.of(
                new AdminSessionDto("sess-target", "target-id", "target_user", "10.0.0.1", "Agent",
                        Instant.now(), Instant.now(), Instant.now().plusSeconds(1800))
        );
        when(sessionRedisService.getSessionsForAdmin("target-id")).thenReturn(sessions);

        List<AdminSessionDto> result = sessionManagementService.getAdminSessions("target-id", superActor);
        assertThat(result).hasSize(1);

        sessionManagementService.revokeSession("target-id", "sess-target", superActor);
        verify(sessionRedisService).revokeSession("sess-target");

        sessionManagementService.revokeAllSessions("target-id", superActor);
        verify(sessionRedisService).revokeAllSessionsForAdmin("target-id");
    }

    @Test
    @DisplayName("S-07: OPS_ADMIN can view sessions of Tier 3 admin")
    void testOpsAdminCanViewTier3Sessions() {
        AdminPrincipal opsActor = AdminPrincipal.builder()
                .id("ops-id")
                .username("ops_admin")
                .roles(List.of("OPERATIONS_ADMIN"))
                .permissions(List.of(new GrantDto("session:read_any", List.of("*"))))
                .build();

        Admin svcAdmin = Admin.builder().id("svc-id").username("svc_maker").build();
        Role svcRole = Role.builder().code("SERVICE_ADMIN").tier(3).build();

        when(adminRepository.findById("svc-id")).thenReturn(Optional.of(svcAdmin));
        when(adminRoleRepository.findByAdminId("svc-id")).thenReturn(List.of(
                AdminRole.builder().role(svcRole).build()
        ));

        List<AdminSessionDto> sessions = List.of(
                new AdminSessionDto("sess-svc-1", "svc-id", "svc_maker", "10.0.0.2", "Agent",
                        Instant.now(), Instant.now(), Instant.now().plusSeconds(1800))
        );
        when(sessionRedisService.getSessionsForAdmin("svc-id")).thenReturn(sessions);

        List<AdminSessionDto> result = sessionManagementService.getAdminSessions("svc-id", opsActor);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).sessionId()).isEqualTo("sess-svc-1");
    }
}
