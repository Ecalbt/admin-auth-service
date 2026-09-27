package com.example.adminauth.dto.catalog;

import lombok.Builder;

import java.util.List;

@Builder
public record RoleDto(
        Long id,
        String code,
        String name,
        Integer tier,
        String description,
        List<PermissionDto> presetPermissions
) {}
