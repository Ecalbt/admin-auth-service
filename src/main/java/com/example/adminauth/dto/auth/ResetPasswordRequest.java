package com.example.adminauth.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank(message = "Reset token cannot be blank")
        String resetToken,

        String totpCode,

        String backupCode,

        @NotBlank(message = "New password cannot be blank")
        @Size(min = 12, message = "Password must be at least 12 characters long")
        String newPassword
) {}
