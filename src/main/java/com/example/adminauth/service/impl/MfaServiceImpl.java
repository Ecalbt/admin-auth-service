package com.example.adminauth.service.impl;

import com.example.adminauth.dto.mfa.BackupCodesResponse;
import com.example.adminauth.dto.mfa.TotpSetupResponse;
import com.example.adminauth.entity.Admin;
import com.example.adminauth.entity.BackupCode;
import com.example.adminauth.entity.TotpSecret;
import com.example.adminauth.exception.BusinessException;
import com.example.adminauth.exception.ResourceNotFoundException;
import com.example.adminauth.repository.AdminRepository;
import com.example.adminauth.repository.BackupCodeRepository;
import com.example.adminauth.repository.TotpSecretRepository;
import com.example.adminauth.security.jwt.JwtTokenProvider;
import com.example.adminauth.service.MfaService;
import com.warrenstrange.googleauth.GoogleAuthenticator;
import com.warrenstrange.googleauth.GoogleAuthenticatorKey;
import com.warrenstrange.googleauth.GoogleAuthenticatorQRGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MfaServiceImpl implements MfaService {

    private final TotpSecretRepository totpSecretRepository;
    private final BackupCodeRepository backupCodeRepository;
    private final AdminRepository adminRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final com.example.adminauth.security.session.SessionRedisService sessionRedisService;
    private final com.example.adminauth.service.AuditService auditService;
    private final com.example.adminauth.messaging.NotificationService notificationService;

    @Value("${app.mfa.issuer:OCB-AutoEarning-Admin}")
    private String issuer;

    private final GoogleAuthenticator gAuth = new GoogleAuthenticator();

    @Override
    @Transactional
    public TotpSetupResponse setupTotp(String adminId) {
        Admin admin = adminRepository.findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found with id: " + adminId));

        GoogleAuthenticatorKey key = gAuth.createCredentials();
        String secret = key.getKey();

        TotpSecret totpSecret = totpSecretRepository.findByAdminId(adminId)
                .orElseGet(() -> TotpSecret.builder().admin(admin).build());

        totpSecret.setSecret(secret);
        totpSecret.setIsConfirmed(false);
        totpSecret.setConfirmedAt(null);
        totpSecretRepository.save(totpSecret);

        String otpAuthTotpURL = GoogleAuthenticatorQRGenerator.getOtpAuthTotpURL(issuer, admin.getUsername(), key);
        return new TotpSetupResponse(secret, otpAuthTotpURL, secret);
    }

    @Override
    @Transactional
    public BackupCodesResponse confirmTotp(String adminId, int verificationCode) {
        TotpSecret totpSecret = totpSecretRepository.findByAdminId(adminId)
                .orElseThrow(() -> new BusinessException("MFA TOTP setup has not been initiated for this account"));

        boolean isCodeValid = gAuth.authorize(totpSecret.getSecret(), verificationCode);
        if (!isCodeValid) {
            throw new BusinessException("Invalid verification code. Please check your authenticator app.");
        }

        totpSecret.setIsConfirmed(true);
        totpSecret.setConfirmedAt(LocalDateTime.now());
        totpSecretRepository.save(totpSecret);

        List<String> rawCodes = generateBackupCodes(totpSecret.getAdmin());

        // Audit & Notification for MFA_ENABLED
        auditService.recordEvent(adminId, "MFA_ENABLED", adminId, null, "TOTP MFA enabled and backup codes generated", null, null, null);
        notificationService.sendNotification(
                com.example.adminauth.event.NotificationEventType.MFA_ENABLED,
                adminId,
                totpSecret.getAdmin().getUsername(),
                totpSecret.getAdmin().getEmail(),
                java.util.Collections.emptyMap(),
                adminId,
                totpSecret.getAdmin().getUsername(),
                "TOTP MFA enabled",
                null
        );

        return new BackupCodesResponse(rawCodes);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyTotp(String adminId, int code) {
        if (sessionRedisService.isTotpCodeUsed(adminId, code)) {
            log.warn("TOTP REPLAY DETECTED for adminId: {} with code: {}", adminId, code);
            return false;
        }

        TotpSecret totpSecret = totpSecretRepository.findByAdminId(adminId)
                .filter(TotpSecret::getIsConfirmed)
                .orElse(null);

        if (totpSecret == null) {
            return false;
        }

        boolean valid = gAuth.authorize(totpSecret.getSecret(), code);
        if (valid) {
            sessionRedisService.markTotpCodeUsed(adminId, code, java.time.Duration.ofSeconds(90));
        }
        return valid;
    }

    @Override
    @Transactional
    public boolean verifyBackupCode(String adminId, String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return false;
        }

        String codeHash = jwtTokenProvider.hashToken(rawCode.trim().toUpperCase());
        BackupCode backupCode = backupCodeRepository.findByAdminIdAndCodeHashAndUsedAtIsNull(adminId, codeHash)
                .orElse(null);

        if (backupCode != null) {
            backupCode.setUsedAt(LocalDateTime.now());
            backupCodeRepository.save(backupCode);
            log.info("Backup code used successfully for adminId: {}", adminId);
            return true;
        }

        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isMfaConfigured(String adminId) {
        return totpSecretRepository.findByAdminId(adminId)
                .map(TotpSecret::getIsConfirmed)
                .orElse(false);
    }

    private List<String> generateBackupCodes(Admin admin) {
        backupCodeRepository.deleteByAdminId(admin.getId());

        List<String> rawCodes = new ArrayList<>();
        SecureRandom random = new SecureRandom();
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

        for (int i = 0; i < 10; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < 8; j++) {
                sb.append(chars.charAt(random.nextInt(chars.length())));
            }
            String raw = sb.toString();
            rawCodes.add(raw);

            BackupCode bc = BackupCode.builder()
                    .admin(admin)
                    .codeHash(jwtTokenProvider.hashToken(raw))
                    .build();
            backupCodeRepository.save(bc);
        }

        return rawCodes;
    }
}
