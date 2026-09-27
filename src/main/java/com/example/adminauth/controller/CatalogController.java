package com.example.adminauth.controller;

import com.example.adminauth.common.ApiResponse;
import com.example.adminauth.dto.catalog.PermissionDto;
import com.example.adminauth.dto.catalog.RoleDto;
import com.example.adminauth.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Catalog", description = "Roles and Permissions Metadata Catalog")
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @Operation(summary = "Get list of all predefined roles and their default preset permissions")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/roles")
    public ApiResponse<List<RoleDto>> getAllRoles() {
        return ApiResponse.ok(catalogService.getAllRoles());
    }

    @Operation(summary = "Get list of all system permissions")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/permissions")
    public ApiResponse<List<PermissionDto>> getAllPermissions() {
        return ApiResponse.ok(catalogService.getAllPermissions());
    }
}
