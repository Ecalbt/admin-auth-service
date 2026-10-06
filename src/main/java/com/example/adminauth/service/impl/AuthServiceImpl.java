package com.example.adminauth.service.impl;

import com.example.adminauth.dto.auth.*;
import com.example.adminauth.entity.*;
import com.example.adminauth.exception.BusinessException;
import com.example.adminauth.exception.ResourceNotFoundException;
import com.example.adminauth.mapper.AdminMapper;
import com.example.adminauth.repository.*;
import com.example.adminauth.security.AdminPrincipal;
import com.example.adminauth.security.jwt.GrantDto;
import com.example.adminauth.security.jwt.JwtTokenProvider;
import com.example.adminauth.security.session.SessionRedisService;
import com.example.adminauth.service.AuditService;
import com.example.adminauth.service.AuthService;
import com.example.adminauth.service.MfaService;
import com.example.adminauth.dto.mfa.BackupCodesResponse;
import com.example.adminauth.dto.mfa.TotpSetupResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AdminRepository adminRepository;
    private final AdminRoleRepository adminRoleRepository;
    private final AdminPermissionRepository adminPermissionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordHistoryRepository passwordHistoryRepository;
    private final SessionRedisService sessionRedisService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final MfaService mfaService;
    private final AuditService auditService;
    private final AdminMapper adminMapper;
    private final com.example.adminauth.messaging.NotificationService notificationService;

    @Value("${app.security.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${app.security.lockout-duration-minutes:30}")
    private int lockoutDurationMinutes;

    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&])[A-Za-z\\d@$!%*?&]{12,}$");

    @Override
    @Transactional
    public LoginResponse login(LoginRequest req, String ipAddress, String userAgent) {
        Admin admin = adminRepository.findByUsername(req.username())
                .orElseThrow(() -> new BadCredentialsException("Invalid username or password"));

        checkAccountLockoutAndStatus(admin);

        if (!passwordEncoder.matches(req.password(), admin.getPasswordHash())) {
            int attempts = admin.getFailedLoginAttempts() + 1;
            admin.setFailedLoginAttempts(attempts);
            if (attempts >= maxFailedAttempts) {
                admin.setStatus(AdminStatus.LOCKED);
                admin.setLockedUntil(LocalDateTime.now().plusMinutes(lockoutDurationMinutes));
                auditService.recordEvent(admin.getUsername(), "ACCOUNT_LOCKED", admin.getId(),
                        null, "Account locked after " + attempts + " failed attempts", ipAddress, userAgent, null);
                notificationService.sendNotification(
                        com.example.adminauth.event.NotificationEventType.ACCOUNT_LOCKED,
                        admin.getId(),
                        admin.getUsername(),
                        admin.getEmail(),
                        Map.of("failedAttempts", String.valueOf(attempts), "lockoutMinutes", String.valueOf(lockoutDurationMinutes)),
                        "SYSTEM",
                        "SYSTEM",
                        "Account locked due to 5 consecutive failed login attempts",
                        null
                );
                adminRepository.save(admin);
                throw new LockedException("Account is locked due to too many failed login attempts. Try again in " + lockoutDurationMinutes + " minutes.");
            }
            adminRepository.save(admin);
            auditService.recordEvent(admin.getUsername(), "LOGIN_FAILED", admin.getId(),
                    null, "Incorrect password (attempt " + attempts + ")", ipAddress, userAgent, null);
            throw new BadCredentialsException("Invalid username or password");
        }

        if (admin.getFailedLoginAttempts() > 0) {
            admin.setFailedLoginAttempts(0);
            admin.setLockedUntil(null);
            adminRepository.save(admin);
        }

        // Check if account requires mandatory first-time onboarding:
        // Status is PENDING_ACTIVATION, OR mustChangePassword is true AND MFA is not configured
        boolean requiresOnboarding = admin.getStatus() == AdminStatus.PENDING_ACTIVATION
                || (Boolean.TRUE.equals(admin.getMustChangePassword()) && !mfaService.isMfaConfigured(admin.getId()));

        if (requiresOnboarding) {
            String rawToken = jwtTokenProvider.generateSecureRandomToken();
            String fullOnboardingToken = rawToken + ":" + admin.getId();

            sessionRedisService.saveOnboardingToken(admin.getId(), rawToken, Duration.ofMinutes(15));

            TotpSetupResponse totpSetup = mfaService.setupTotp(admin.getId());

            auditService.recordEvent(admin.getUsername(), "ONBOARDING_REQUIRED", admin.getId(),
                    null, "Onboarding and MFA enrollment required for first login", ipAddress, userAgent, null);

            return LoginResponse.builder()
                    .mfaRequired(false)
                    .onboardingRequired(true)
                    .onboardingToken(fullOnboardingToken)
                    .totpSecretKey(totpSetup.secretKey())
                    .totpQrCodeUri(totpSetup.qrCodeDataUri())
                    .username(admin.getUsername())
                    .fullName(admin.getFullName())
                    .mustChangePassword(true)
                    .build();
        }

        if (mfaService.isMfaConfigured(admin.getId())) {
            String rawMfaToken = jwtTokenProvider.generateSecureRandomToken();
            sessionRedisService.saveMfaChallengeToken(admin.getId(), rawMfaToken, Duration.ofMinutes(5));

            return LoginResponse.builder()
                    .mfaRequired(true)
                    .mfaToken(rawMfaToken + ":" + admin.getId())
                    .username(admin.getUsername())
                    .fullName(admin.getFullName())
                    .build();
        }

        return issueTokens(admin, false, ipAddress, userAgent);
    }

    @Override
    @Transactional
    public TotpSetupResponse setupOnboardingMfa(String fullOnboardingToken) {
        String adminId = validateAndExtractOnboardingAdminId(fullOnboardingToken);
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new BadCredentialsException("Invalid onboarding session"));

        return mfaService.setupTotp(admin.getId());
    }

    @Override
    @Transactional
    public LoginResponse completeOnboarding(CompleteOnboardingRequest req, String ipAddress, String userAgent) {
        String adminId = validateAndExtractOnboardingAdminId(req.onboardingToken());
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new BadCredentialsException("Invalid onboarding session"));

        // 1. Validate password policy
        if (req.newPassword() == null || !PASSWORD_PATTERN.matcher(req.newPassword()).matches()) {
            throw new BusinessException("Password must be at least 12 characters and contain uppercase, lowercase, digit, and special character (@$!%*?&)");
        }

        // 2. Cannot reuse temporary password
        if (passwordEncoder.matches(req.newPassword(), admin.getPasswordHash())) {
            throw new BusinessException("New password cannot be the same as the temporary password");
        }

        // 3. Password history check
        for (PasswordHistory ph : passwordHistoryRepository.findRecentHistory(admin.getId(), PageRequest.of(0, 5))) {
            if (passwordEncoder.matches(req.newPassword(), ph.getPasswordHash())) {
                throw new BusinessException("Password has been used recently. Please choose a different password.");
            }
        }

        // 4. Verify TOTP code and generate backup codes
        BackupCodesResponse backupCodesResp = mfaService.confirmTotp(adminId, req.totpCode());

        // 5. Update password & activate account
        String newHash = passwordEncoder.encode(req.newPassword());
        admin.setPasswordHash(newHash);
        admin.setStatus(AdminStatus.ACTIVE);
        admin.setMustChangePassword(false);
        admin.setUpdatedBy(admin.getUsername());
        adminRepository.save(admin);

        PasswordHistory history = PasswordHistory.builder()
                .admin(admin)
                .passwordHash(newHash)
                .build();
        passwordHistoryRepository.save(history);

        // 6. Invalidate onboarding token
        sessionRedisService.removeOnboardingToken(adminId);

        auditService.recordEvent(admin.getUsername(), "ONBOARDING_COMPLETED", admin.getId(),
                null, "First-time password changed and MFA enabled successfully", ipAddress, userAgent, null);

        // 7. Issue active tokens (mfaVerified = true)
        LoginResponse loginResp = issueTokens(admin, true, ipAddress, userAgent);

        return LoginResponse.builder()
                .mfaRequired(false)
                .onboardingRequired(false)
                .accessToken(loginResp.accessToken())
                .refreshToken(loginResp.refreshToken())
                .expiresIn(loginResp.expiresIn())
                .adminId(loginResp.adminId())
                .username(loginResp.username())
                .fullName(loginResp.fullName())
                .mustChangePassword(false)
                .roles(loginResp.roles())
                .permissions(loginResp.permissions())
                .backupCodes(backupCodesResp.backupCodes())
                .build();
    }

    private String validateAndExtractOnboardingAdminId(String fullOnboardingToken) {
        if (fullOnboardingToken == null || !fullOnboardingToken.contains(":")) {
            throw new BadCredentialsException("Invalid onboarding token format");
        }
        String[] parts = fullOnboardingToken.split(":");
        if (parts.length < 2) {
            throw new BadCredentialsException("Invalid onboarding token format");
        }
        String rawToken = parts[0];
        String adminId = parts[1];

        if (!sessionRedisService.validateOnboardingToken(adminId, rawToken)) {
            throw new BadCredentialsException("Onboarding token is invalid or has expired");
        }
        return adminId;
    }

    @Override
    @Transactional
    public LoginResponse verifyMfa(MfaVerifyRequest req, String ipAddress, String userAgent) {
        if (req.mfaToken() == null || !req.mfaToken().contains(":")) {
            throw new BadCredentialsException("Invalid MFA token format");
        }
        String[] parts = req.mfaToken().split(":");
        if (parts.length < 2) {
            throw new BadCredentialsException("Invalid MFA token format");
        }
        String rawToken = parts[0];
        String adminId = parts[1];

        if (!sessionRedisService.validateMfaChallengeToken(adminId, rawToken)) {
            throw new BadCredentialsException("MFA session is invalid or has expired");
        }

        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new BadCredentialsException("Invalid MFA session"));

        boolean verified = false;
        if (req.totpCode() != null && !req.totpCode().isBlank()) {
            try {
                int code = Integer.parseInt(req.totpCode().trim());
                verified = mfaService.verifyTotp(adminId, code);
            } catch (NumberFormatException ignored) {}
        }

        if (!verified && req.backupCode() != null && !req.backupCode().isBlank()) {
            verified = mfaService.verifyBackupCode(adminId, req.backupCode());
        }

        if (!verified) {
            auditService.recordEvent(admin.getUsername(), "MFA_FAILED", admin.getId(),
                    null, "MFA code verification failed", ipAddress, userAgent, null);
            throw new BadCredentialsException("Invalid MFA code");
        }

        sessionRedisService.removeMfaChallengeToken(adminId);

        auditService.recordEvent(admin.getUsername(), "MFA_VERIFIED", admin.getId(),
                null, "MFA verified successfully", ipAddress, userAgent, null);

        return issueTokens(admin, true, ipAddress, userAgent);
    }

    @Override
    @Transactional
    public LoginResponse refreshToken(RefreshTokenRequest req, String ipAddress, String userAgent) {
        String rawToken = req.refreshToken();
        String tokenHash = jwtTokenProvider.hashToken(rawToken);

        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));

        if (refreshToken.isRevoked()) {
            log.warn("TOKEN REUSE DETECTED for familyId: {}! Possible token theft!", refreshToken.getFamilyId());
            refreshTokenRepository.revokeFamily(refreshToken.getFamilyId(), LocalDateTime.now());
            sessionRedisService.revokeSession(refreshToken.getSessionId());

            auditService.recordEvent(
                    refreshToken.getAdmin().getUsername(), "TOKEN_REUSE_DETECTED",
                    refreshToken.getAdmin().getId(), null,
                    "Revoked token family " + refreshToken.getFamilyId(), ipAddress, userAgent, null
            );

            notificationService.sendNotification(
                    com.example.adminauth.event.NotificationEventType.TOKEN_REUSE_DETECTED,
                    refreshToken.getAdmin().getId(),
                    refreshToken.getAdmin().getUsername(),
                    refreshToken.getAdmin().getEmail(),
                    Map.of("familyId", refreshToken.getFamilyId()),
                    "SYSTEM",
                    "SYSTEM",
                    "Security Alert: Refresh token reuse detected",
                    null
            );

            throw new BadCredentialsException("Security Alert: Token reuse detected. All active tokens have been revoked.");
        }

        if (refreshToken.isExpired()) {
            throw new BadCredentialsException("Refresh token has expired. Please login again.");
        }

        Admin admin = refreshToken.getAdmin();
        if (admin.getStatus() != AdminStatus.ACTIVE && admin.getStatus() != AdminStatus.PENDING_ACTIVATION) {
            throw new DisabledException("Account is not active");
        }

        String newRawRefreshToken = jwtTokenProvider.generateSecureRandomToken();
        String newTokenHash = jwtTokenProvider.hashToken(newRawRefreshToken);

        refreshToken.setRevokedAt(LocalDateTime.now());
        refreshToken.setReplacedBy(newTokenHash);
        refreshTokenRepository.save(refreshToken);

        RefreshToken newRefreshToken = RefreshToken.builder()
                .admin(admin)
                .sessionId(refreshToken.getSessionId())
                .tokenHash(newTokenHash)
                .familyId(refreshToken.getFamilyId())
                .expiresAt(LocalDateTime.now().plusSeconds(jwtTokenProvider.getRefreshTokenExpirationSeconds()))
                .build();
        refreshTokenRepository.save(newRefreshToken);

        sessionRedisService.touchSession(refreshToken.getSessionId());

        List<String> roles = getAdminRoleCodes(admin.getId());
        List<GrantDto> permissions = getAdminGrants(admin.getId(), roles);

        String newAccessToken = jwtTokenProvider.generateAccessToken(
                admin.getId(), admin.getUsername(), admin.getEmail(), refreshToken.getSessionId(),
                roles, permissions, true
        );

        return LoginResponse.builder()
                .mfaRequired(false)
                .accessToken(newAccessToken)
                .refreshToken(newRawRefreshToken)
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationSeconds())
                .adminId(admin.getId())
                .username(admin.getUsername())
                .fullName(admin.getFullName())
                .mustChangePassword(admin.getMustChangePassword())
                .roles(roles)
                .permissions(permissions)
                .build();
    }

    @Override
    @Transactional
    public void logout(String sessionId, AdminPrincipal actor) {
        if (sessionId != null) {
            sessionRedisService.revokeSession(sessionId);
            refreshTokenRepository.revokeSession(sessionId, LocalDateTime.now());
            auditService.recordEvent(actor.getUsername(), "LOGOUT", actor.getId(), null, "Session ended", null, null, null);
        }
    }

    @Override
    @Transactional
    public void changePassword(AdminPrincipal actor, ChangePasswordRequest req) {
        Admin admin = adminRepository.findById(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));

        if (!passwordEncoder.matches(req.oldPassword(), admin.getPasswordHash())) {
            throw new BadCredentialsException("Old password does not match");
        }

        if (!PASSWORD_PATTERN.matcher(req.newPassword()).matches()) {
            throw new BusinessException("Password must be at least 12 characters and contain uppercase, lowercase, digit, and special characters");
        }

        if (req.newPassword().contains(admin.getUsername())) {
            throw new BusinessException("Password must not contain your username");
        }

        List<PasswordHistory> recentHistory = passwordHistoryRepository.findRecentHistory(admin.getId(), PageRequest.of(0, 5));
        for (PasswordHistory ph : recentHistory) {
            if (passwordEncoder.matches(req.newPassword(), ph.getPasswordHash())) {
                throw new BusinessException("New password cannot be identical to any of your last 5 passwords");
            }
        }

        PasswordHistory historyRecord = PasswordHistory.builder()
                .admin(admin)
                .passwordHash(admin.getPasswordHash())
                .build();
        passwordHistoryRepository.save(historyRecord);

        admin.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        admin.setMustChangePassword(false);
        adminRepository.save(admin);

        sessionRedisService.revokeAllSessionsForAdmin(admin.getId());
        refreshTokenRepository.revokeAllForAdmin(admin.getId(), LocalDateTime.now());

        auditService.recordEvent(admin.getUsername(), "PASSWORD_CHANGED", admin.getId(), null, "Password changed successfully", null, null, null);
        notificationService.sendNotification(
                com.example.adminauth.event.NotificationEventType.PASSWORD_CHANGED,
                admin.getId(),
                admin.getUsername(),
                admin.getEmail(),
                java.util.Collections.emptyMap(),
                admin.getId(),
                admin.getUsername(),
                "Password changed successfully",
                null
        );
    }

    @Override
    @Transactional
    public ForgotPasswordResponse forgotPassword(ForgotPasswordRequest req, String ipAddress, String userAgent) {
        Admin admin = adminRepository.findByUsername(req.username())
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found with username: " + req.username()));

        if (admin.getStatus() == AdminStatus.DISABLED) {
            throw new DisabledException("Account has been disabled by administrator");
        }

        if (!mfaService.isMfaConfigured(admin.getId())) {
            throw new BusinessException("MFA is not configured for this account. Please contact an administrator to reset your password.");
        }

        String rawToken = jwtTokenProvider.generateSecureRandomToken();
        String fullResetToken = rawToken + ":" + admin.getId();

        sessionRedisService.savePasswordResetToken(admin.getId(), rawToken, Duration.ofMinutes(10));

        auditService.recordEvent(admin.getUsername(), "PASSWORD_FORGOT_REQUESTED", admin.getId(),
                null, "Password reset initiated via TOTP", ipAddress, userAgent, null);

        return new ForgotPasswordResponse(
                fullResetToken,
                "TOTP",
                "Please submit the 6-digit TOTP code from your authenticator app along with your new password to complete the reset process."
        );
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest req, String ipAddress, String userAgent) {
        if (req.resetToken() == null || !req.resetToken().contains(":")) {
            throw new BadCredentialsException("Invalid password reset token format");
        }
        String[] parts = req.resetToken().split(":");
        if (parts.length < 2) {
            throw new BadCredentialsException("Invalid password reset token format");
        }
        String rawToken = parts[0];
        String adminId = parts[1];

        if (!sessionRedisService.validatePasswordResetToken(adminId, rawToken)) {
            throw new BadCredentialsException("Password reset token is invalid or has expired");
        }

        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new BadCredentialsException("Invalid password reset session"));

        if (admin.getStatus() == AdminStatus.DISABLED) {
            throw new DisabledException("Account has been disabled by administrator");
        }

        // 1. Verify TOTP or backup code
        boolean verified = false;
        if (req.totpCode() != null && !req.totpCode().isBlank()) {
            try {
                int code = Integer.parseInt(req.totpCode().trim());
                verified = mfaService.verifyTotp(adminId, code);
            } catch (NumberFormatException ignored) {}
        }
        if (!verified && req.backupCode() != null && !req.backupCode().isBlank()) {
            verified = mfaService.verifyBackupCode(adminId, req.backupCode().trim());
        }

        if (!verified) {
            auditService.recordEvent(admin.getUsername(), "PASSWORD_RESET_FAILED", admin.getId(),
                    null, "MFA code verification failed during password reset", ipAddress, userAgent, null);
            throw new BadCredentialsException("Invalid MFA verification code");
        }

        // 2. Validate new password pattern
        if (req.newPassword() == null || !PASSWORD_PATTERN.matcher(req.newPassword()).matches()) {
            throw new BusinessException("Password must be at least 12 characters and contain uppercase, lowercase, digit, and special character (@$!%*?&)");
        }

        // 3. Check against current password
        if (passwordEncoder.matches(req.newPassword(), admin.getPasswordHash())) {
            throw new BusinessException("New password cannot be the same as the current password");
        }

        // 4. Check against password history
        for (PasswordHistory ph : passwordHistoryRepository.findRecentHistory(admin.getId(), PageRequest.of(0, 5))) {
            if (passwordEncoder.matches(req.newPassword(), ph.getPasswordHash())) {
                throw new BusinessException("Password has been used recently. Please choose a different password.");
            }
        }

        // 5. Update password
        String newHash = passwordEncoder.encode(req.newPassword());
        admin.setPasswordHash(newHash);
        if (admin.getStatus() == AdminStatus.LOCKED) {
            admin.setStatus(AdminStatus.ACTIVE);
        }
        admin.setFailedLoginAttempts(0);
        admin.setLockedUntil(null);
        admin.setMustChangePassword(false);
        admin.setUpdatedBy(admin.getUsername());
        adminRepository.save(admin);

        PasswordHistory history = PasswordHistory.builder()
                .admin(admin)
                .passwordHash(newHash)
                .build();
        passwordHistoryRepository.save(history);

        // 6. Revoke all sessions & refresh tokens
        sessionRedisService.revokeAllSessionsForAdmin(adminId);
        refreshTokenRepository.revokeAllForAdmin(adminId, LocalDateTime.now());

        // 7. Remove reset token
        sessionRedisService.removePasswordResetToken(adminId);

        auditService.recordEvent(admin.getUsername(), "PASSWORD_RESET_SELF", admin.getId(),
                null, "Password reset successfully via TOTP self-service", ipAddress, userAgent, null);
    }

    private LoginResponse issueTokens(Admin admin, boolean mfaVerified, String ipAddress, String userAgent) {
        var session = sessionRedisService.createSession(admin.getId(), admin.getUsername(), ipAddress, userAgent);

        List<String> roles = getAdminRoleCodes(admin.getId());
        List<GrantDto> permissions = getAdminGrants(admin.getId(), roles);

        String accessToken = jwtTokenProvider.generateAccessToken(
                admin.getId(), admin.getUsername(), admin.getEmail(), session.sessionId(),
                roles, permissions, mfaVerified
        );

        String rawRefreshToken = jwtTokenProvider.generateSecureRandomToken();
        String tokenHash = jwtTokenProvider.hashToken(rawRefreshToken);
        String familyId = UUID.randomUUID().toString();

        RefreshToken refreshToken = RefreshToken.builder()
                .admin(admin)
                .sessionId(session.sessionId())
                .tokenHash(tokenHash)
                .familyId(familyId)
                .expiresAt(LocalDateTime.now().plusSeconds(jwtTokenProvider.getRefreshTokenExpirationSeconds()))
                .build();
        refreshTokenRepository.save(refreshToken);

        auditService.recordEvent(admin.getUsername(), "LOGIN_SUCCESS", admin.getId(), null, "Login successful", ipAddress, userAgent, null);

        return LoginResponse.builder()
                .mfaRequired(false)
                .accessToken(accessToken)
                .refreshToken(rawRefreshToken)
                .expiresIn(jwtTokenProvider.getAccessTokenExpirationSeconds())
                .adminId(admin.getId())
                .username(admin.getUsername())
                .fullName(admin.getFullName())
                .mustChangePassword(admin.getMustChangePassword())
                .roles(roles)
                .permissions(permissions)
                .build();
    }

    private List<String> getAdminRoleCodes(String adminId) {
        return adminRoleRepository.findByAdminId(adminId).stream()
                .map(ar -> ar.getRole().getCode())
                .toList();
    }

    private List<GrantDto> getAdminGrants(String adminId, List<String> roles) {
        if (roles.contains("SUPERADMIN")) {
            return List.of(new GrantDto("*", List.of("*")));
        }

        return adminMapper.toGrantDtoList(
                adminPermissionRepository.findByAdminIdAndRevokedAtIsNull(adminId)
        );
    }

    private void checkAccountLockoutAndStatus(Admin admin) {
        if (admin.getStatus() == AdminStatus.DISABLED) {
            throw new DisabledException("Account has been disabled by administrator");
        }

        if (admin.getStatus() == AdminStatus.LOCKED) {
            if (admin.getLockedUntil() != null && LocalDateTime.now().isBefore(admin.getLockedUntil())) {
                throw new LockedException("Account is locked until " + admin.getLockedUntil());
            } else {
                admin.setStatus(AdminStatus.ACTIVE);
                admin.setFailedLoginAttempts(0);
                admin.setLockedUntil(null);
                adminRepository.save(admin);
            }
        }
    }
}
