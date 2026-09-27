package com.example.adminauth.mapper;

import com.example.adminauth.dto.catalog.PermissionDto;
import com.example.adminauth.dto.catalog.RoleDto;
import com.example.adminauth.entity.Permission;
import com.example.adminauth.entity.Role;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mapper chuyển đổi Role và Permission entity sang DTO.
 */
@Component
public class CatalogMapper {

    public RoleDto toRoleDto(Role role) {
        if (role == null) return null;

        List<PermissionDto> presetDtos = role.getPresetPermissions() != null
                ? role.getPresetPermissions().stream().map(this::toPermissionDto).toList()
                : List.of();

        return RoleDto.builder()
                .id(role.getId())
                .code(role.getCode())
                .name(role.getName())
                .tier(role.getTier())
                .description(role.getDescription())
                .presetPermissions(presetDtos)
                .build();
    }

    public List<RoleDto> toRoleDtoList(List<Role> roles) {
        if (roles == null) return List.of();
        return roles.stream().map(this::toRoleDto).toList();
    }

    public PermissionDto toPermissionDto(Permission permission) {
        if (permission == null) return null;

        return PermissionDto.builder()
                .id(permission.getId())
                .code(permission.getCode())
                .name(permission.getName())
                .category(permission.getCategory())
                .description(permission.getDescription())
                .build();
    }

    public List<PermissionDto> toPermissionDtoList(List<Permission> permissions) {
        if (permissions == null) return List.of();
        return permissions.stream().map(this::toPermissionDto).toList();
    }
}
