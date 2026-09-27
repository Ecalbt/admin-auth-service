package com.example.adminauth.dto.admin;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record GrantRequestDto(
        @NotBlank(message = "Permission code is required")
        String permissionCode,

        List<String> scopes
) {}
