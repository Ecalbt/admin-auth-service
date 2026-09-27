package com.example.adminauth.dto.admin;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record AssignRoleRequest(
        @NotEmpty(message = "At least one role code is required")
        List<String> roleCodes,

        List<GrantRequestDto> customGrants
) {}
