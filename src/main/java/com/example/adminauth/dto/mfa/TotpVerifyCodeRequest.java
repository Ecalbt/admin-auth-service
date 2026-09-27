package com.example.adminauth.dto.mfa;

import jakarta.validation.constraints.NotBlank;

public record TotpVerifyCodeRequest(
        @NotBlank(message = "Verification code is required")
        String code
) {}
