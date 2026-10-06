package com.example.adminauth.service;

import com.example.adminauth.dto.auth.ChangePasswordRequest;
import com.example.adminauth.dto.auth.CompleteOnboardingRequest;
import com.example.adminauth.dto.auth.LoginRequest;
import com.example.adminauth.dto.auth.LoginResponse;
import com.example.adminauth.dto.auth.MfaVerifyRequest;
import com.example.adminauth.dto.auth.RefreshTokenRequest;
import com.example.adminauth.dto.mfa.TotpSetupResponse;
import com.example.adminauth.security.jwt.GrantDto;
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
import org.springframework.security.authentication.DisabledException;
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
    @Mock
    private com.example.adminauth.messaging.NotificationService notificationService;
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

    @Test
    @DisplayName("Forgot password issues reset token for account with configured MFA")
    void testForgotPasswordSuccess() {
        Admin admin = Admin.builder()
                .id("adm-fp")
                .username("forgot_user")
                .status(AdminStatus.ACTIVE)
                .build();

        when(adminRepository.findByUsername("forgot_user")).thenReturn(Optional.of(admin));
        when(mfaService.isMfaConfigured("adm-fp")).thenReturn(true);
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("raw-reset-token");

        com.example.adminauth.dto.auth.ForgotPasswordResponse resp = authService.forgotPassword(
                new com.example.adminauth.dto.auth.ForgotPasswordRequest("forgot_user"), "127.0.0.1", "Agent"
        );

        assertThat(resp).isNotNull();
        assertThat(resp.resetToken()).isEqualTo("raw-reset-token:adm-fp");
        assertThat(resp.method()).isEqualTo("TOTP");
        verify(sessionRedisService).savePasswordResetToken(eq("adm-fp"), eq("raw-reset-token"), any());
    }

    @Test
    @DisplayName("Forgot password throws exception when account has no MFA configured")
    void testForgotPasswordFailsWhenMfaNotConfigured() {
        Admin admin = Admin.builder()
                .id("adm-no-mfa")
                .username("no_mfa_user")
                .status(AdminStatus.ACTIVE)
                .build();

        when(adminRepository.findByUsername("no_mfa_user")).thenReturn(Optional.of(admin));
        when(mfaService.isMfaConfigured("adm-no-mfa")).thenReturn(false);

        assertThatThrownBy(() -> authService.forgotPassword(
                new com.example.adminauth.dto.auth.ForgotPasswordRequest("no_mfa_user"), "127.0.0.1", "Agent"))
                .isInstanceOf(com.example.adminauth.exception.BusinessException.class)
                .hasMessageContaining("MFA is not configured");
    }

    @Test
    @DisplayName("Reset password with valid TOTP code updates password and revokes all sessions")
    void testResetPasswordSuccessWithTotp() {
        Admin admin = Admin.builder()
                .id("adm-reset")
                .username("reset_user")
                .passwordHash("old_hash")
                .status(AdminStatus.LOCKED)
                .failedLoginAttempts(5)
                .mustChangePassword(true)
                .build();

        when(sessionRedisService.validatePasswordResetToken("adm-reset", "reset-token")).thenReturn(true);
        when(adminRepository.findById("adm-reset")).thenReturn(Optional.of(admin));
        when(mfaService.verifyTotp("adm-reset", 123456)).thenReturn(true);
        when(passwordEncoder.matches("NewSecurePass@123", "old_hash")).thenReturn(false);
        when(passwordHistoryRepository.findRecentHistory(eq("adm-reset"), any())).thenReturn(List.of());
        when(passwordEncoder.encode("NewSecurePass@123")).thenReturn("new_hash");

        var req = new com.example.adminauth.dto.auth.ResetPasswordRequest(
                "reset-token:adm-reset", "123456", null, "NewSecurePass@123"
        );

        authService.resetPassword(req, "127.0.0.1", "Agent");

        assertThat(admin.getPasswordHash()).isEqualTo("new_hash");
        assertThat(admin.getStatus()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(admin.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(admin.getMustChangePassword()).isFalse();

        verify(sessionRedisService).revokeAllSessionsForAdmin("adm-reset");
        verify(refreshTokenRepository).revokeAllForAdmin(eq("adm-reset"), any());
        verify(sessionRedisService).removePasswordResetToken("adm-reset");
        verify(passwordHistoryRepository).save(any(com.example.adminauth.entity.PasswordHistory.class));
    }

    @Test
    @DisplayName("Reset password fails when TOTP code is invalid")
    void testResetPasswordFailsWithInvalidTotp() {
        Admin admin = Admin.builder()
                .id("adm-reset")
                .username("reset_user")
                .status(AdminStatus.ACTIVE)
                .build();

        when(sessionRedisService.validatePasswordResetToken("adm-reset", "reset-token")).thenReturn(true);
        when(adminRepository.findById("adm-reset")).thenReturn(Optional.of(admin));
        when(mfaService.verifyTotp("adm-reset", 999999)).thenReturn(false);

        var req = new com.example.adminauth.dto.auth.ResetPasswordRequest(
                "reset-token:adm-reset", "999999", null, "NewSecurePass@123"
        );

        assertThatThrownBy(() -> authService.resetPassword(req, "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA verification code");
    }

    @Test
    @DisplayName("Verify MFA succeeds when challenge token and TOTP are valid")
    void testVerifyMfaSuccess() {
        Admin admin = Admin.builder()
                .id("adm-mfa")
                .username("mfa_user")
                .email("mfa@ocb.com.vn")
                .status(AdminStatus.ACTIVE)
                .build();

        when(sessionRedisService.validateMfaChallengeToken("adm-mfa", "valid-challenge-token")).thenReturn(true);
        when(adminRepository.findById("adm-mfa")).thenReturn(Optional.of(admin));
        when(mfaService.verifyTotp("adm-mfa", 123456)).thenReturn(true);

        AdminSessionDto sessionDto = new AdminSessionDto(
                "sess-mfa", "adm-mfa", "mfa_user", "127.0.0.1", "Agent",
                Instant.now(), Instant.now(), Instant.now().plusSeconds(1800)
        );
        when(sessionRedisService.createSession(eq("adm-mfa"), eq("mfa_user"), any(), any())).thenReturn(sessionDto);
        when(adminRoleRepository.findByAdminId("adm-mfa")).thenReturn(List.of());
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn("access_token_jwt");
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("raw_rt");
        when(jwtTokenProvider.hashToken("raw_rt")).thenReturn("rt_hash");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        var req = new com.example.adminauth.dto.auth.MfaVerifyRequest("valid-challenge-token:adm-mfa", "123456", null, null);
        LoginResponse resp = authService.verifyMfa(req, "127.0.0.1", "Agent");

        assertThat(resp).isNotNull();
        assertThat(resp.accessToken()).isEqualTo("access_token_jwt");
        verify(sessionRedisService).removeMfaChallengeToken("adm-mfa");
    }

    @Test
    @DisplayName("Verify MFA fails when challenge token is invalid or expired in Redis")
    void testVerifyMfaFailsWithInvalidChallengeToken() {
        when(sessionRedisService.validateMfaChallengeToken("adm-mfa", "forged-token")).thenReturn(false);

        var req = new com.example.adminauth.dto.auth.MfaVerifyRequest("forged-token:adm-mfa", "123456", null, null);

        assertThatThrownBy(() -> authService.verifyMfa(req, "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("MFA session is invalid or has expired");
    }

    // --- L-02 to L-08: Login Scenarios from TEST_CASES.md ---

    @Test
    @DisplayName("L-02: Login with non-existent username throws BadCredentialsException")
    void testLoginUserNotFound() {
        when(adminRepository.findByUsername("ghost_user")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost_user", "AnyPassword@123"), "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid username or password");
    }

    @Test
    @DisplayName("L-03: Single failed password attempt increments failure count to 1")
    void testLoginSingleFailedAttempt() {
        Admin admin = Admin.builder()
                .id("adm-fail-1")
                .username("fail_user")
                .passwordHash("hashed")
                .status(AdminStatus.ACTIVE)
                .failedLoginAttempts(0)
                .build();

        when(adminRepository.findByUsername("fail_user")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("WrongPass", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("fail_user", "WrongPass"), "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class);

        assertThat(admin.getFailedLoginAttempts()).isEqualTo(1);
        verify(adminRepository).save(admin);
    }

    @Test
    @DisplayName("L-05: Login when account is currently LOCKED throws LockedException")
    void testLoginLockedAccountStillInDuration() {
        Admin admin = Admin.builder()
                .id("adm-locked")
                .username("locked_user")
                .status(AdminStatus.LOCKED)
                .lockedUntil(LocalDateTime.now().plusMinutes(20))
                .build();

        when(adminRepository.findByUsername("locked_user")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> authService.login(new LoginRequest("locked_user", "AnyPass"), "127.0.0.1", "Agent"))
                .isInstanceOf(LockedException.class)
                .hasMessageContaining("Account is locked until");
    }

    @Test
    @DisplayName("L-06: Login when lockedUntil is in the past automatically unlocks account")
    void testLoginLockedAccountExpiredAutoUnlocks() {
        Admin admin = Admin.builder()
                .id("adm-unlocked")
                .username("unlocked_user")
                .passwordHash("hashed")
                .status(AdminStatus.LOCKED)
                .failedLoginAttempts(5)
                .lockedUntil(LocalDateTime.now().minusMinutes(5))
                .mustChangePassword(false)
                .build();

        when(adminRepository.findByUsername("unlocked_user")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("ValidPassword@123", "hashed")).thenReturn(true);
        when(mfaService.isMfaConfigured("adm-unlocked")).thenReturn(false);

        AdminSessionDto sessionDto = new AdminSessionDto(
                "sess-unlocked", "adm-unlocked", "unlocked_user", "127.0.0.1", "Agent",
                Instant.now(), Instant.now(), Instant.now().plusSeconds(1800)
        );
        when(sessionRedisService.createSession(any(), any(), any(), any())).thenReturn(sessionDto);
        when(adminRoleRepository.findByAdminId("adm-unlocked")).thenReturn(List.of());
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn("jwt");
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("rt");
        when(jwtTokenProvider.hashToken("rt")).thenReturn("rt_hash");

        LoginResponse resp = authService.login(new LoginRequest("unlocked_user", "ValidPassword@123"), "127.0.0.1", "Agent");

        assertThat(resp).isNotNull();
        assertThat(admin.getStatus()).isEqualTo(AdminStatus.ACTIVE);
        assertThat(admin.getFailedLoginAttempts()).isEqualTo(0);
        assertThat(admin.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("L-07: Login with DISABLED account throws DisabledException")
    void testLoginDisabledAccount() {
        Admin admin = Admin.builder()
                .id("adm-disabled")
                .username("disabled_user")
                .status(AdminStatus.DISABLED)
                .build();

        when(adminRepository.findByUsername("disabled_user")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> authService.login(new LoginRequest("disabled_user", "Pass@123"), "127.0.0.1", "Agent"))
                .isInstanceOf(DisabledException.class)
                .hasMessageContaining("Account has been disabled");
    }

    @Test
    @DisplayName("L-08: Correct login after failed attempts resets counter to 0")
    void testLoginResetAttemptsOnSuccess() {
        Admin admin = Admin.builder()
                .id("adm-reset-attempts")
                .username("reset_attempts_user")
                .passwordHash("hashed")
                .status(AdminStatus.ACTIVE)
                .failedLoginAttempts(2)
                .mustChangePassword(false)
                .build();

        when(adminRepository.findByUsername("reset_attempts_user")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("CorrectPass@123", "hashed")).thenReturn(true);
        when(mfaService.isMfaConfigured("adm-reset-attempts")).thenReturn(false);

        AdminSessionDto sessionDto = new AdminSessionDto(
                "sess-1", "adm-reset-attempts", "reset_attempts_user", "127.0.0.1", "Agent",
                Instant.now(), Instant.now(), Instant.now().plusSeconds(1800)
        );
        when(sessionRedisService.createSession(any(), any(), any(), any())).thenReturn(sessionDto);
        when(adminRoleRepository.findByAdminId("adm-reset-attempts")).thenReturn(List.of());
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn("jwt");
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("rt");
        when(jwtTokenProvider.hashToken("rt")).thenReturn("rt_hash");

        authService.login(new LoginRequest("reset_attempts_user", "CorrectPass@123"), "127.0.0.1", "Agent");

        assertThat(admin.getFailedLoginAttempts()).isEqualTo(0);
        verify(adminRepository, atLeastOnce()).save(admin);
    }

    // --- M-02 to M-05: MFA Verification Scenarios ---

    @Test
    @DisplayName("M-02: MFA verify with malformed token (no colon) throws BadCredentialsException")
    void testVerifyMfaMalformedToken() {
        var req = new com.example.adminauth.dto.auth.MfaVerifyRequest("malformedtokenwithoutcolon", "123456", null, null);

        assertThatThrownBy(() -> authService.verifyMfa(req, "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA token format");
    }

    @Test
    @DisplayName("M-03: MFA verify with invalid TOTP code throws BadCredentialsException")
    void testVerifyMfaInvalidTotpCode() {
        Admin admin = Admin.builder().id("adm-mfa-fail").username("mfa_fail_user").build();
        when(sessionRedisService.validateMfaChallengeToken("adm-mfa-fail", "token123")).thenReturn(true);
        when(adminRepository.findById("adm-mfa-fail")).thenReturn(Optional.of(admin));
        when(mfaService.verifyTotp("adm-mfa-fail", 0)).thenReturn(false);

        var req = new com.example.adminauth.dto.auth.MfaVerifyRequest("token123:adm-mfa-fail", "000000", null, null);

        assertThatThrownBy(() -> authService.verifyMfa(req, "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA code");
    }

    @Test
    @DisplayName("M-04: MFA verify succeeds using valid backup code fallback")
    void testVerifyMfaWithBackupCode() {
        Admin admin = Admin.builder()
                .id("adm-backup")
                .username("backup_user")
                .email("backup@ocb.com.vn")
                .status(AdminStatus.ACTIVE)
                .build();

        when(sessionRedisService.validateMfaChallengeToken("adm-backup", "token-bk")).thenReturn(true);
        when(adminRepository.findById("adm-backup")).thenReturn(Optional.of(admin));
        when(mfaService.verifyBackupCode("adm-backup", "A1B2C3D4")).thenReturn(true);

        AdminSessionDto sessionDto = new AdminSessionDto(
                "sess-bk", "adm-backup", "backup_user", "127.0.0.1", "Agent",
                Instant.now(), Instant.now(), Instant.now().plusSeconds(1800)
        );
        when(sessionRedisService.createSession(any(), any(), any(), any())).thenReturn(sessionDto);
        when(adminRoleRepository.findByAdminId("adm-backup")).thenReturn(List.of());
        when(jwtTokenProvider.generateAccessToken(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn("jwt");
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("rt");
        when(jwtTokenProvider.hashToken("rt")).thenReturn("rt_hash");
        when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var req = new com.example.adminauth.dto.auth.MfaVerifyRequest("token-bk:adm-backup", null, null, "A1B2C3D4");
        LoginResponse resp = authService.verifyMfa(req, "127.0.0.1", "Agent");

        assertThat(resp).isNotNull();
        assertThat(resp.accessToken()).isEqualTo("jwt");
        verify(sessionRedisService).removeMfaChallengeToken("adm-backup");
    }

    // --- O-02 to O-05: Onboarding Edge Cases ---

    @Test
    @DisplayName("O-02: Onboarding with invalid or expired token throws BadCredentialsException")
    void testOnboardingInvalidToken() {
        when(sessionRedisService.validateOnboardingToken("adm-1", "expired-token")).thenReturn(false);

        var req = new com.example.adminauth.dto.auth.CompleteOnboardingRequest(
                "expired-token:adm-1", "NewSecurePass@123", 123456
        );

        assertThatThrownBy(() -> authService.completeOnboarding(req, "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Onboarding token is invalid or has expired");
    }

    @Test
    @DisplayName("O-03: Onboarding with weak password throws BusinessException")
    void testOnboardingWeakPassword() {
        Admin admin = Admin.builder().id("adm-weak").username("weak_user").passwordHash("temp_hash").build();
        when(sessionRedisService.validateOnboardingToken("adm-weak", "token-weak")).thenReturn(true);
        when(adminRepository.findById("adm-weak")).thenReturn(Optional.of(admin));

        var req = new com.example.adminauth.dto.auth.CompleteOnboardingRequest(
                "token-weak:adm-weak", "weakpass", 123456
        );

        assertThatThrownBy(() -> authService.completeOnboarding(req, "127.0.0.1", "Agent"))
                .isInstanceOf(com.example.adminauth.exception.BusinessException.class)
                .hasMessageContaining("Password must be at least 12 characters");
    }

    @Test
    @DisplayName("O-04: Onboarding with newPassword identical to temporary password throws BusinessException")
    void testOnboardingMatchesTemporaryPassword() {
        Admin admin = Admin.builder().id("adm-same").username("same_user").passwordHash("temp_hash").build();
        when(sessionRedisService.validateOnboardingToken("adm-same", "token-same")).thenReturn(true);
        when(adminRepository.findById("adm-same")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("TempPassword@123", "temp_hash")).thenReturn(true);

        var req = new com.example.adminauth.dto.auth.CompleteOnboardingRequest(
                "token-same:adm-same", "TempPassword@123", 123456
        );

        assertThatThrownBy(() -> authService.completeOnboarding(req, "127.0.0.1", "Agent"))
                .isInstanceOf(com.example.adminauth.exception.BusinessException.class)
                .hasMessageContaining("New password cannot be the same as the temporary password");
    }

    // --- P-02 to P-05: Change Password Scenarios ---

    @Test
    @DisplayName("P-02: Change password fails when old password does not match")
    void testChangePasswordOldPasswordMismatch() {
        Admin admin = Admin.builder().id("adm-cp").username("cp_user").passwordHash("hashed").build();
        when(adminRepository.findById("adm-cp")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("WrongOld@123", "hashed")).thenReturn(false);

        AdminPrincipal actor = AdminPrincipal.builder().id("adm-cp").username("cp_user").build();
        ChangePasswordRequest req = new ChangePasswordRequest("WrongOld@123", "NewSecurePass@123");

        assertThatThrownBy(() -> authService.changePassword(actor, req))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Old password does not match");
    }

    @Test
    @DisplayName("P-04: Change password fails when new password contains username")
    void testChangePasswordContainingUsername() {
        Admin admin = Admin.builder().id("adm-cp2").username("adminjohn").passwordHash("hashed").build();
        when(adminRepository.findById("adm-cp2")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("OldPass@123", "hashed")).thenReturn(true);

        AdminPrincipal actor = AdminPrincipal.builder().id("adm-cp2").username("adminjohn").build();
        ChangePasswordRequest req = new ChangePasswordRequest("OldPass@123", "NewPass@adminjohn123");

        assertThatThrownBy(() -> authService.changePassword(actor, req))
                .isInstanceOf(com.example.adminauth.exception.BusinessException.class)
                .hasMessageContaining("Password must not contain your username");
    }

    @Test
    @DisplayName("P-05: Change password fails when new password matches one of last 5 passwords")
    void testChangePasswordReusedHistory() {
        Admin admin = Admin.builder().id("adm-cp3").username("cp3_user").passwordHash("hashed").build();
        when(adminRepository.findById("adm-cp3")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("OldPass@123", "hashed")).thenReturn(true);

        var hist = com.example.adminauth.entity.PasswordHistory.builder().passwordHash("hist_hash").build();
        when(passwordHistoryRepository.findRecentHistory(eq("adm-cp3"), any())).thenReturn(List.of(hist));
        when(passwordEncoder.matches("ReusedPass@123", "hist_hash")).thenReturn(true);

        AdminPrincipal actor = AdminPrincipal.builder().id("adm-cp3").username("cp3_user").build();
        ChangePasswordRequest req = new ChangePasswordRequest("OldPass@123", "ReusedPass@123");

        assertThatThrownBy(() -> authService.changePassword(actor, req))
                .isInstanceOf(com.example.adminauth.exception.BusinessException.class)
                .hasMessageContaining("New password cannot be identical to any of your last 5 passwords");
    }

    // --- R-02 to R-05: Refresh Token Scenarios ---

    @Test
    @DisplayName("R-02: Refresh token not found throws BadCredentialsException")
    void testRefreshTokenNotFound() {
        when(jwtTokenProvider.hashToken("random-rt")).thenReturn("rt-hash");
        when(refreshTokenRepository.findByTokenHash("rt-hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequest("random-rt"), "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid refresh token");
    }

    @Test
    @DisplayName("R-04: Expired refresh token throws BadCredentialsException")
    void testRefreshTokenExpired() {
        RefreshToken token = RefreshToken.builder()
                .tokenHash("hash-exp")
                .expiresAt(LocalDateTime.now().minusMinutes(10))
                .build();

        when(jwtTokenProvider.hashToken("expired-rt")).thenReturn("hash-exp");
        when(refreshTokenRepository.findByTokenHash("hash-exp")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequest("expired-rt"), "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Refresh token has expired");
    }

    @Test
    @DisplayName("R-05: Refresh token for DISABLED admin throws DisabledException")
    void testRefreshTokenDisabledAccount() {
        Admin disabledAdmin = Admin.builder().id("adm-dis").status(AdminStatus.DISABLED).build();
        RefreshToken token = RefreshToken.builder()
                .tokenHash("hash-dis")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .admin(disabledAdmin)
                .build();

        when(jwtTokenProvider.hashToken("valid-rt")).thenReturn("hash-dis");
        when(refreshTokenRepository.findByTokenHash("hash-dis")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.refreshToken(new RefreshTokenRequest("valid-rt"), "127.0.0.1", "Agent"))
                .isInstanceOf(DisabledException.class)
                .hasMessageContaining("Account is not active");
    }

    // --- L-10 & L-11: Login with mustChangePassword scenarios ---

    @Test
    @DisplayName("L-10: Login after password reset without MFA configured leads to onboarding")
    void testLoginMustChangePasswordWithoutMfa() {
        Admin admin = Admin.builder()
                .id("adm-rst")
                .username("rst_user")
                .passwordHash("hashed")
                .status(AdminStatus.ACTIVE)
                .mustChangePassword(true)
                .build();
        when(adminRepository.findByUsername("rst_user")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("TempPass@123", "hashed")).thenReturn(true);
        when(mfaService.isMfaConfigured("adm-rst")).thenReturn(false);
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("onboard-token");
        when(mfaService.setupTotp("adm-rst")).thenReturn(new TotpSetupResponse("secret", "qrUri", "raw"));

        LoginResponse resp = authService.login(new LoginRequest("rst_user", "TempPass@123"), "127.0.0.1", "Agent");

        assertThat(resp.onboardingRequired()).isTrue();
        assertThat(resp.onboardingToken()).isEqualTo("onboard-token:adm-rst");
        assertThat(resp.totpSecretKey()).isEqualTo("secret");
    }

    @Test
    @DisplayName("L-11: Login with mustChangePassword=true but already has MFA configured prompts MFA")
    void testLoginMustChangePasswordWithMfaConfigured() {
        Admin admin = Admin.builder()
                .id("adm-rst-mfa")
                .username("rst_mfa_user")
                .passwordHash("hashed")
                .status(AdminStatus.ACTIVE)
                .mustChangePassword(true)
                .build();
        when(adminRepository.findByUsername("rst_mfa_user")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("TempPass@123", "hashed")).thenReturn(true);
        when(mfaService.isMfaConfigured("adm-rst-mfa")).thenReturn(true);
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("mfa-token");

        LoginResponse resp = authService.login(new LoginRequest("rst_mfa_user", "TempPass@123"), "127.0.0.1", "Agent");

        assertThat(resp.mfaRequired()).isTrue();
        assertThat(resp.mfaToken()).isEqualTo("mfa-token:adm-rst-mfa");
        assertThat(resp.onboardingRequired()).isNull();
    }

    // --- M-05 & M-06: Additional MFA Verify Scenarios ---

    @Test
    @DisplayName("M-05: Backup code cannot be reused (single-use)")
    void testVerifyMfaBackupCodeReused() {
        Admin admin = Admin.builder().id("adm-bk").username("bk_user").build();
        when(sessionRedisService.validateMfaChallengeToken("adm-bk", "mfa-token")).thenReturn(true);
        when(adminRepository.findById("adm-bk")).thenReturn(Optional.of(admin));
        when(mfaService.verifyBackupCode("adm-bk", "ALREADYUSED")).thenReturn(false);

        MfaVerifyRequest req = new MfaVerifyRequest("mfa-token:adm-bk", null, null, "ALREADYUSED");
        assertThatThrownBy(() -> authService.verifyMfa(req, "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA code");
    }

    @Test
    @DisplayName("M-06: Non-numeric TOTP code and invalid backup code both fail")
    void testVerifyMfaNonNumericTotpAndBadBackupCode() {
        Admin admin = Admin.builder().id("adm-nonnum").username("nonnum_user").build();
        when(sessionRedisService.validateMfaChallengeToken("adm-nonnum", "mfa-token")).thenReturn(true);
        when(adminRepository.findById("adm-nonnum")).thenReturn(Optional.of(admin));
        when(mfaService.verifyBackupCode("adm-nonnum", "badbackup")).thenReturn(false);

        MfaVerifyRequest req = new MfaVerifyRequest("mfa-token:adm-nonnum", "abcxyz", null, "badbackup");
        assertThatThrownBy(() -> authService.verifyMfa(req, "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid MFA code");
    }

    // --- O-06, O-07, O-08: Onboarding Scenarios ---

    @Test
    @DisplayName("O-06: Onboarding fails when TOTP confirmation code is invalid")
    void testCompleteOnboardingInvalidTotpCode() {
        Admin admin = Admin.builder().id("adm-ob-totp").username("ob_totp_user").passwordHash("oldhash").build();
        when(sessionRedisService.validateOnboardingToken("adm-ob-totp", "raw-token")).thenReturn(true);
        when(adminRepository.findById("adm-ob-totp")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("NewSecurePass@123", "oldhash")).thenReturn(false);
        when(mfaService.confirmTotp("adm-ob-totp", 999999)).thenThrow(
                new com.example.adminauth.exception.BusinessException("Invalid verification code. Please check your authenticator app.")
        );

        CompleteOnboardingRequest req = new CompleteOnboardingRequest(
                "raw-token:adm-ob-totp", "NewSecurePass@123", 999999
        );

        assertThatThrownBy(() -> authService.completeOnboarding(req, "127.0.0.1", "Agent"))
                .isInstanceOf(com.example.adminauth.exception.BusinessException.class)
                .hasMessageContaining("Invalid verification code");
    }

    @Test
    @DisplayName("O-07: Get QR setup during onboarding with valid onboarding token")
    void testSetupOnboardingMfaSuccess() {
        Admin admin = Admin.builder().id("adm-setup").username("setup_user").build();
        when(sessionRedisService.validateOnboardingToken("adm-setup", "raw-ob-tok")).thenReturn(true);
        when(adminRepository.findById("adm-setup")).thenReturn(Optional.of(admin));
        when(mfaService.setupTotp("adm-setup")).thenReturn(new TotpSetupResponse("new-secret", "new-qr", "new-secret"));

        TotpSetupResponse resp = authService.setupOnboardingMfa("raw-ob-tok:adm-setup");
        assertThat(resp).isNotNull();
        assertThat(resp.secretKey()).isEqualTo("new-secret");
        assertThat(resp.qrCodeDataUri()).isEqualTo("new-qr");
    }

    @Test
    @DisplayName("O-08: MFA becomes mandatory after setup (login requires MFA)")
    void testLoginRequiresMfaAfterSetup() {
        Admin admin = Admin.builder()
                .id("adm-mfa-req")
                .username("mfa_user")
                .passwordHash("hashed")
                .status(AdminStatus.ACTIVE)
                .mustChangePassword(false)
                .build();
        when(adminRepository.findByUsername("mfa_user")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("ValidPassword@123", "hashed")).thenReturn(true);
        when(mfaService.isMfaConfigured("adm-mfa-req")).thenReturn(true);
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("mfa-token-123");

        LoginResponse resp = authService.login(new LoginRequest("mfa_user", "ValidPassword@123"), "127.0.0.1", "Agent");

        assertThat(resp.mfaRequired()).isTrue();
        assertThat(resp.mfaToken()).isEqualTo("mfa-token-123:adm-mfa-req");
        assertThat(resp.accessToken()).isNull();
    }

    // --- R-01 & R-06: Refresh Token Rotation & Permissions ---

    @Test
    @DisplayName("R-01: Valid refresh token rotation issues new access token & refresh token and revokes old")
    void testRefreshTokenRotationSuccess() {
        Admin admin = Admin.builder()
                .id("adm-rf")
                .username("rf_user")
                .email("rf@ocb.com.vn")
                .status(AdminStatus.ACTIVE)
                .mustChangePassword(false)
                .build();
        RefreshToken oldToken = RefreshToken.builder()
                .id(1L)
                .admin(admin)
                .sessionId("sess-rf")
                .familyId("fam-rf")
                .tokenHash("old-hash")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();

        when(jwtTokenProvider.hashToken("raw-old-rt")).thenReturn("old-hash");
        when(refreshTokenRepository.findByTokenHash("old-hash")).thenReturn(Optional.of(oldToken));
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("raw-new-rt");
        when(jwtTokenProvider.hashToken("raw-new-rt")).thenReturn("new-hash");
        when(jwtTokenProvider.getRefreshTokenExpirationSeconds()).thenReturn(86400L);
        when(jwtTokenProvider.getAccessTokenExpirationSeconds()).thenReturn(1800L);
        when(jwtTokenProvider.generateAccessToken(eq("adm-rf"), eq("rf_user"), eq("rf@ocb.com.vn"), eq("sess-rf"), any(), any(), eq(true)))
                .thenReturn("new-jwt-access-token");

        LoginResponse resp = authService.refreshToken(new RefreshTokenRequest("raw-old-rt"), "127.0.0.1", "Agent");

        assertThat(resp).isNotNull();
        assertThat(resp.accessToken()).isEqualTo("new-jwt-access-token");
        assertThat(resp.refreshToken()).isEqualTo("raw-new-rt");
        assertThat(oldToken.getRevokedAt()).isNotNull();
        assertThat(oldToken.getReplacedBy()).isEqualTo("new-hash");
        verify(refreshTokenRepository).save(oldToken);
        verify(refreshTokenRepository).save(argThat(rt -> "new-hash".equals(rt.getTokenHash()) && "fam-rf".equals(rt.getFamilyId())));
        verify(sessionRedisService).touchSession("sess-rf");
    }

    @Test
    @DisplayName("R-06: Refresh token reflects updated grants loaded from DB")
    void testRefreshTokenReflectsUpdatedGrants() {
        Admin admin = Admin.builder()
                .id("adm-rf-grant")
                .username("grant_user")
                .email("grant@ocb.com.vn")
                .status(AdminStatus.ACTIVE)
                .mustChangePassword(false)
                .build();
        RefreshToken oldToken = RefreshToken.builder()
                .id(2L)
                .admin(admin)
                .sessionId("sess-rf2")
                .familyId("fam-rf2")
                .tokenHash("old-hash-2")
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();

        Role updatedRole = Role.builder().code("OPERATIONS_ADMIN").build();
        AdminRole ar = AdminRole.builder().role(updatedRole).build();

        when(jwtTokenProvider.hashToken("raw-grant-rt")).thenReturn("old-hash-2");
        when(refreshTokenRepository.findByTokenHash("old-hash-2")).thenReturn(Optional.of(oldToken));
        when(jwtTokenProvider.generateSecureRandomToken()).thenReturn("raw-new-rt2");
        when(jwtTokenProvider.hashToken("raw-new-rt2")).thenReturn("new-hash-2");
        when(jwtTokenProvider.getRefreshTokenExpirationSeconds()).thenReturn(86400L);
        when(jwtTokenProvider.getAccessTokenExpirationSeconds()).thenReturn(1800L);
        when(adminRoleRepository.findByAdminId("adm-rf-grant")).thenReturn(List.of(ar));
        when(jwtTokenProvider.generateAccessToken(eq("adm-rf-grant"), eq("grant_user"), eq("grant@ocb.com.vn"), eq("sess-rf2"), eq(List.of("OPERATIONS_ADMIN")), any(), eq(true)))
                .thenReturn("new-jwt-with-ops-role");

        LoginResponse resp = authService.refreshToken(new RefreshTokenRequest("raw-grant-rt"), "127.0.0.1", "Agent");

        assertThat(resp).isNotNull();
        assertThat(resp.roles()).containsExactly("OPERATIONS_ADMIN");
        assertThat(resp.accessToken()).isEqualTo("new-jwt-with-ops-role");
    }

    // --- P-03: Change Password Policy Failure ---

    @Test
    @DisplayName("P-03: Change password fails when new password does not meet policy")
    void testChangePasswordPolicyFailure() {
        Admin admin = Admin.builder().id("adm-cp4").username("cp4_user").passwordHash("hashed").build();
        when(adminRepository.findById("adm-cp4")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("OldPass@123", "hashed")).thenReturn(true);

        AdminPrincipal actor = AdminPrincipal.builder().id("adm-cp4").username("cp4_user").build();
        ChangePasswordRequest req = new ChangePasswordRequest("OldPass@123", "weakpass");

        assertThatThrownBy(() -> authService.changePassword(actor, req))
                .isInstanceOf(com.example.adminauth.exception.BusinessException.class)
                .hasMessageContaining("Password must be at least 12 characters");
    }

    // --- CONC-05: Double-submit Complete Onboarding ---

    @Test
    @DisplayName("CONC-05: Double-submit complete onboarding fails on second attempt")
    void testDoubleSubmitCompleteOnboarding() {
        when(sessionRedisService.validateOnboardingToken("adm-ob2", "token-once")).thenReturn(false);

        CompleteOnboardingRequest req = new CompleteOnboardingRequest(
                "token-once:adm-ob2", "ValidPassword@123", 123456
        );

        assertThatThrownBy(() -> authService.completeOnboarding(req, "127.0.0.1", "Agent"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Onboarding token is invalid or has expired");
    }
}
