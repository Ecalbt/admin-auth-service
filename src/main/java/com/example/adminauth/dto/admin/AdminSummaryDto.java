package com.example.adminauth.dto.admin;

import lombok.Builder;

import java.time.LocalDateTime;
import java.util.List;

@Builder
public record AdminSummaryDto(
        String id,
        String username,
        String email,
        String fullName,
        String status,
        List<String> roles,
        LocalDateTime createdAt
) {}
