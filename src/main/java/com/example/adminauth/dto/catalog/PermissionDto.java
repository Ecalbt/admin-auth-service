package com.example.adminauth.dto.catalog;

import lombok.Builder;

@Builder
public record PermissionDto(
        Long id,
        String code,
        String name,
        String category,
        String description
) {}
