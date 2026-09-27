package com.example.adminauth.service.impl;

import com.example.adminauth.dto.catalog.PermissionDto;
import com.example.adminauth.dto.catalog.RoleDto;
import com.example.adminauth.mapper.CatalogMapper;
import com.example.adminauth.repository.PermissionRepository;
import com.example.adminauth.repository.RoleRepository;
import com.example.adminauth.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CatalogServiceImpl implements CatalogService {

    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final CatalogMapper catalogMapper;

    @Override
    @Transactional(readOnly = true)
    public List<RoleDto> getAllRoles() {
        return catalogMapper.toRoleDtoList(roleRepository.findAll());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionDto> getAllPermissions() {
        return catalogMapper.toPermissionDtoList(permissionRepository.findAll());
    }
}
