package com.example.adminauth.dto.admin;

import com.example.adminauth.security.jwt.GrantDto;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record AdminDetailDto(
        String id,
        String username,
        String email,
        String fullName,
        String status,
        Boolean mustChangePassword,
        Integer failedLoginAttempts,
        LocalDateTime lockedUntil,
        List<String> roles,
        List<GrantDto> permissions,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
