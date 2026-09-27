package com.example.adminauth.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record MfaVerifyRequest(
        @NotBlank(message = "MFA token cannot be blank")
        String mfaToken,

        String totpCode,
        String emailOtp,
        String backupCode
) {}
