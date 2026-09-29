package com.example.adminauth.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CompleteOnboardingRequest(
        @NotBlank(message = "Onboarding token cannot be blank")
        String onboardingToken,

        @NotBlank(message = "New password cannot be blank")
        String newPassword,

        @NotNull(message = "TOTP code is required")
        Integer totpCode
) {}
