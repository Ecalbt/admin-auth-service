package com.example.adminauth.service;

import com.example.adminauth.dto.catalog.PermissionDto;
import com.example.adminauth.dto.catalog.RoleDto;

import java.util.List;

/**
 * Service Interface định nghĩa các thao tác tra cứu danh mục Roles & Permissions.
 */
public interface CatalogService {

    List<RoleDto> getAllRoles();

    List<PermissionDto> getAllPermissions();
}
