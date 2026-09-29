package com.example.adminauth.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record ForgotPasswordRequest(
        @NotBlank(message = "Username cannot be blank")
        String username
) {}
