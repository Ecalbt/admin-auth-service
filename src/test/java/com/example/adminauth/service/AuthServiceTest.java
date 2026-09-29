package com.example.adminauth.service;

import com.example.adminauth.dto.auth.ChangePasswordRequest;
import com.example.adminauth.dto.auth.LoginRequest;
import com.example.adminauth.dto.auth.LoginResponse;
import com.example.adminauth.dto.auth.RefreshTokenRequest;
import com.example.adminauth.dto.session.AdminSessionDto;
import com.example.adminauth.entity.Admin;
import com.example.adminauth.entity.AdminRole;
import com.example.adminauth.entity.AdminStatus;
import com.example.adminauth.entity.RefreshToken;
import com.example.adminauth.entity.Role;
import com.example.adminauth.repository.*;
import com.example.adminauth.security.AdminPrincipal;
import com.example.adminauth.security.jwt.JwtTokenProvider;
import com.example.adminauth.security.session.SessionRedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AdminRepository adminRepository;
    @Mock
    private AdminRoleRepository adminRoleRepository;
    @Mock
    private AdminPermissionRepository adminPermissionRepository;
    @Mock
    private RefreshTokenRepository refreshTokenRepository;
    @Mock
    private PasswordHistoryRepository passwordHistoryRepository;
    @Mock
    private SessionRedisService sessionRedisService;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private MfaService mfaService;
    @Mock
    private AuditService auditService;
    @Mock
    private com.example.adminauth.mapper.AdminMapper adminMapper;
    @Spy
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks
    private com.example.adminauth.service.impl.AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "maxFailedAttempts", 5);
        ReflectionTestUtils.setField(authService, "lockoutDurationMinutes", 30);
    }

    @Test
    @DisplayName("Successful login without MFA returns tokens")
    void testLoginSuccess() {
        Admin admin = Admin.builder()
                .id("adm-1")
                .username("test_user")
                .email("test@ocb.com.vn")
                .passwordHash("hashed_pw")
                .status(AdminStatus.ACTIVE)
                .failedLoginAttempts(0)
                .mustChangePassword(false)
                .build();

        when(adminRepository.findByUsername("test_user")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("ValidPassword@123", "hashed_pw")).thenReturn(true);
        when(mfaService.isMfaConfigured("adm-1")).thenReturn(false);

        AdminSessionDto sessionDto = new AdminSessionDto(
                "sess-1", "adm-1", "test_user", "127.0.0.1", "Agent",
                Instant.now(), Instant.now(), Instant.now().plusSeconds(1800)
        );
        when(sessionRedisService.createSession(eq("adm-1"), eq("test_user"), any(), any())).thenReturn(sessionDto);
        when(adminRoleRepository.findByAdminId("adm-1")).thenReturn(List.of(
                AdminRole.builder().role(Role.builder().code("SERVICE_ADMIN").build()).build()
        ));
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn("access_token_jwt");
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("raw_refresh_token");
        when(jwtTokenProvider.hashToken(any())).thenReturn("hashed_refresh_token");
        when(jwtTokenProvider.getAccessTokenExpirationSeconds()).thenReturn(1800L);
        when(jwtTokenProvider.getRefreshTokenExpirationSeconds()).thenReturn(86400L);

        LoginResponse resp = authService.login(new LoginRequest("test_user", "ValidPassword@123"), "127.0.0.1", "Agent");

        assertThat(resp).isNotNull();
        assertThat(resp.mfaRequired()).isFalse();
        assertThat(resp.accessToken()).isEqualTo("access_token_jwt");
        assertThat(resp.refreshToken()).isEqualTo("raw_refresh_token");
        verify(refreshTokenRepository, times(1)).save(any(RefreshToken.class));
    }

    @Test
    @DisplayName("Password mismatch increments failure count and locks account after 5 tries")
    void testLoginFailureLockout() {
        Admin admin = Admin.builder()
                .id("adm-2")
                .username("lock_user")
                .passwordHash("hashed_pw")
                .status(AdminStatus.ACTIVE)
                .failedLoginAttempts(4)
                .build();

        when(adminRepository.findByUsername("lock_user")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("WrongPassword", "hashed_pw")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("lock_user", "WrongPassword"), "127.0.0.1", "Agent"))
                .isInstanceOf(LockedException.class);

        assertThat(admin.getStatus()).isEqualTo(AdminStatus.LOCKED);
        assertThat(admin.getFailedLoginAttempts()).isEqualTo(5);
        assertThat(admin.getLockedUntil()).isNotNull();
        verify(adminRepository).save(admin);
    }

    @Test
    @DisplayName("Token reuse detection immediately revokes token family and session")
    void testRefreshTokenReuseDetection() {
        RefreshToken revokedToken = RefreshToken.builder()
                .id(10L)
                .familyId("family-99")
                .sessionId("sess-compromised")
                .tokenHash("hash123")
                .revokedAt(LocalDateTime.now().minusMinutes(5)) // already revoked!
                .expiresAt(LocalDateTime.now().plusDays(1))
                .admin(Admin.builder().id("adm-victim").username("victim").build())
                .build();

        when(jwtTokenProvider.hashToken("stolen_token")).thenReturn("hash123");
        when(refreshTokenRepository.findByTokenHash("hash123")).thenReturn(Optional.of(revokedToken));

        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequest("stolen_token"), "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Token reuse detected");

        verify(refreshTokenRepository).revokeFamily(eq("family-99"), any());
        verify(sessionRedisService).revokeSession("sess-compromised");
    }

    @Test
    @DisplayName("Change password enforces history and revokes other sessions")
    void testChangePasswordSuccess() {
        Admin admin = Admin.builder()
                .id("adm-5")
                .username("user5")
                .passwordHash("old_hashed_pw")
                .mustChangePassword(true)
                .build();

        when(adminRepository.findById("adm-5")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("OldPassword@123", "old_hashed_pw")).thenReturn(true);
        when(passwordHistoryRepository.findRecentHistory(eq("adm-5"), any())).thenReturn(List.of());
        when(passwordEncoder.encode("NewValidPassword@456")).thenReturn("new_hashed_pw");

        AdminPrincipal principal = AdminPrincipal.builder().id("adm-5").username("user5").build();
        ChangePasswordRequest req = new ChangePasswordRequest("OldPassword@123", "NewValidPassword@456");

        authService.changePassword(principal, req);

        assertThat(admin.getPasswordHash()).isEqualTo("new_hashed_pw");
        assertThat(admin.getMustChangePassword()).isFalse();
        verify(sessionRedisService).revokeAllSessionsForAdmin("adm-5");
        verify(refreshTokenRepository).revokeAllForAdmin(eq("adm-5"), any());
    }

    @Test
    @DisplayName("First-time login of PENDING_ACTIVATION admin returns onboarding challenge with QR code")
    void testFirstTimeLoginRequiresOnboarding() {
        Admin admin = Admin.builder()
                .id("adm-pending")
                .username("new_admin")
                .email("new@ocb.com.vn")
                .passwordHash("temp_hashed_pw")
                .status(AdminStatus.PENDING_ACTIVATION)
                .failedLoginAttempts(0)
                .mustChangePassword(true)
                .build();

        when(adminRepository.findByUsername("new_admin")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("TempPassword@123", "temp_hashed_pw")).thenReturn(true);
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("random-token");
        when(mfaService.setupTotp("adm-pending")).thenReturn(
                new com.example.adminauth.dto.mfa.TotpSetupResponse("SECRETKEY123", "otpauth://totp/...", "SECRETKEY123")
        );

        LoginResponse resp = authService.login(new LoginRequest("new_admin", "TempPassword@123"), "127.0.0.1", "Agent");

        assertThat(resp.onboardingRequired()).isTrue();
        assertThat(resp.onboardingToken()).isEqualTo("random-token:adm-pending");
        assertThat(resp.totpSecretKey()).isEqualTo("SECRETKEY123");
        assertThat(resp.totpQrCodeUri()).startsWith("otpauth://");
        assertThat(resp.accessToken()).isNull();
        verify(sessionRedisService).saveOnboardingToken(eq("adm-pending"), eq("random-token"), any());
    }

    @Test
    @DisplayName("Complete onboarding activates account, sets new password, confirms MFA, and issues tokens with backup codes")
    void testCompleteOnboardingSuccess() {
        Admin admin = Admin.builder()
                .id("adm-pending")
                .username("new_admin")
                .email("new@ocb.com.vn")
                .passwordHash("temp_hashed_pw")
                .status(AdminStatus.PENDING_ACTIVATION)
                .mustChangePassword(true)
                .build();

        when(sessionRedisService.validateOnboardingToken("adm-pending", "random-token")).thenReturn(true);
        when(adminRepository.findById("adm-pending")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("NewSecurePassword@123", "temp_hashed_pw")).thenReturn(false);
        when(passwordHistoryRepository.findRecentHistory(eq("adm-pending"), any())).thenReturn(List.of());
        when(mfaService.confirmTotp("adm-pending", 123456)).thenReturn(
                new com.example.adminauth.dto.mfa.BackupCodesResponse(List.of("CODE1", "CODE2"))
        );
        when(passwordEncoder.encode("NewSecurePassword@123")).thenReturn("new_hashed_pw");

        AdminSessionDto sessionDto = new AdminSessionDto(
                "sess-onboard", "adm-pending", "new_admin", "127.0.0.1", "Agent",
                Instant.now(), Instant.now(), Instant.now().plusSeconds(1800)
        );
        when(sessionRedisService.createSession(eq("adm-pending"), eq("new_admin"), any(), any())).thenReturn(sessionDto);
        when(adminRoleRepository.findByAdminId("adm-pending")).thenReturn(List.of());
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn("access_token_jwt");
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("raw_rt");
        when(jwtTokenProvider.hashToken("raw_rt")).thenReturn("rt_hash");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        var req = new com.example.adminauth.dto.auth.CompleteOnboardingRequest(
                "random-token:adm-pending", "NewSecurePassword@123", 123456
        );

        LoginResponse resp = authService.completeOnboarding(req, "127.0.0.1", "Agent");

        assertThat(resp).isNotNull();
        assertThat(resp.accessToken()).isEqualTo("access_token_jwt");
        assertThat(resp.backupCodes()).containsExactly("CODE1", "CODE2");
        assertThat(resp.onboardingRequired()).isFalse();
        assertThat(admin.getStatus()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(admin.getMustChangePassword()).isFalse();
        assertThat(admin.getPasswordHash()).isEqualTo("new_hashed_pw");

        verify(sessionRedisService).removeOnboardingToken("adm-pending");
        verify(passwordHistoryRepository).save(any(com.example.adminauth.entity.PasswordHistory.class));
    }
}
