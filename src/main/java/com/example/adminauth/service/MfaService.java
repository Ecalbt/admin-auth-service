package com.example.adminauth.service;

import com.example.adminauth.dto.mfa.BackupCodesResponse;
import com.example.adminauth.dto.mfa.TotpSetupResponse;

/**
 * Service Interface định nghĩa các thao tác Multi-Factor Authentication (TOTP & Backup Codes).
 */
public interface MfaService {

    TotpSetupResponse setupTotp(String adminId);

    BackupCodesResponse confirmTotp(String adminId, int verificationCode);

    boolean verifyTotp(String adminId, int code);

    boolean verifyBackupCode(String adminId, String rawCode);

    boolean isMfaConfigured(String adminId);
}
