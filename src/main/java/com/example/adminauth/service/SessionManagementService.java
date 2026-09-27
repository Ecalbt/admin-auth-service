package com.example.adminauth.service;

import com.example.adminauth.dto.session.AdminSessionDto;
import com.example.adminauth.security.AdminPrincipal;

import java.util.List;

/**
 * Service Interface quản lý phiên đăng nhập và kick session.
 */
public interface SessionManagementService {

    List<AdminSessionDto> getMySessions(AdminPrincipal actor);

    List<AdminSessionDto> getAdminSessions(String adminId, AdminPrincipal actor);

    void revokeSession(String adminId, String sessionId, AdminPrincipal actor);

    void revokeAllSessions(String adminId, AdminPrincipal actor);
}
