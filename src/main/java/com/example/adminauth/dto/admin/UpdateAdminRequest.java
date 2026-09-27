package com.example.adminauth.dto.admin;

import jakarta.validation.constraints.Email;

public record UpdateAdminRequest(
        String fullName,

        @Email(message = "Invalid email format")
        String email
) {}
