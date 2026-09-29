package com.example.adminauth.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record OnboardingMfaSetupRequest(
        @NotBlank(message = "Onboarding token cannot be blank")
        String onboardingToken
) {}
