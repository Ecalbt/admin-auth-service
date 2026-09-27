package com.example.adminauth.mapper;

import com.example.adminauth.dto.admin.AdminDetailDto;
import com.example.adminauth.dto.admin.AdminSummaryDto;
import com.example.adminauth.entity.Admin;
import com.example.adminauth.entity.AdminPermission;
import com.example.adminauth.security.jwt.GrantDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Mapper chuyển đổi giữa Entity Admin/AdminPermission và các DTO tương ứng.
 * Đảm bảo tầng Controller/API không bao giờ tiếp xúc trực tiếp với JPA Entity.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminMapper {

    private final ObjectMapper objectMapper;

    public AdminDetailDto toDetailDto(Admin admin, List<String> roleCodes, List<GrantDto> permissions) {
        if (admin == null) return null;

        return AdminDetailDto.builder()
                .id(admin.getId())
                .username(admin.getUsername())
                .email(admin.getEmail())
                .fullName(admin.getFullName())
                .status(admin.getStatus() != null ? admin.getStatus().name() : null)
                .mustChangePassword(admin.getMustChangePassword())
                .failedLoginAttempts(admin.getFailedLoginAttempts())
                .lockedUntil(admin.getLockedUntil())
                .roles(roleCodes != null ? roleCodes : List.of())
                .permissions(permissions != null ? permissions : List.of())
                .createdBy(admin.getCreatedBy())
                .createdAt(admin.getCreatedAt())
                .updatedAt(admin.getUpdatedAt())
                .build();
    }

    public AdminSummaryDto toSummaryDto(Admin admin, List<String> roleCodes) {
        if (admin == null) return null;

        return AdminSummaryDto.builder()
                .id(admin.getId())
                .username(admin.getUsername())
                .email(admin.getEmail())
                .fullName(admin.getFullName())
                .status(admin.getStatus() != null ? admin.getStatus().name() : null)
                .roles(roleCodes != null ? roleCodes : List.of())
                .createdAt(admin.getCreatedAt())
                .build();
    }

    public GrantDto toGrantDto(AdminPermission adminPermission) {
        if (adminPermission == null) return null;

        List<String> scopes = new ArrayList<>();
        if (adminPermission.getScope() != null) {
            try {
                scopes = objectMapper.readValue(adminPermission.getScope(), new TypeReference<List<String>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse scope JSON for permission {}: {}", adminPermission.getPermission().getCode(), e.getMessage());
                scopes = List.of("*");
            }
        }

        String permCode = adminPermission.getPermission() != null ? adminPermission.getPermission().getCode() : "";
        return new GrantDto(permCode, scopes);
    }

    public List<GrantDto> toGrantDtoList(List<AdminPermission> adminPermissions) {
        if (adminPermissions == null) return List.of();
        return adminPermissions.stream().map(this::toGrantDto).toList();
    }
}
