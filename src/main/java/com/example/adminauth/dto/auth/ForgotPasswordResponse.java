package com.example.adminauth.dto.auth;

public record ForgotPasswordResponse(
        String resetToken,
        String method,
        String message
) {}
