package com.example.adminauth.dto.mfa;

public record TotpSetupResponse(
        String secretKey,
        String qrCodeDataUri,
        String manualEntryKey
) {}
