package com.example.adminauth.security;

import com.example.adminauth.security.jwt.GrantDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Custom Security Evaluator bean accessible in @PreAuthorize via SpEL: @authz.hasPerm(...)
 * Implements Section 4.5.B of PLAN.md
 */
@Slf4j
@Component("authz")
public class CustomAuthEvaluator {

    public boolean hasPerm(String requiredPerm) {
        return hasPerm(requiredPerm, "*");
    }

    /**
     * Checks if current principal possesses requiredPerm for targetServiceId.
     *
     * 1. Bypass SUPERADMIN: returns true if role is SUPERADMIN or grant is {perm: "*", scope: ["*"]}
     * 2. Check grant: returns true if grant matches perm and scope includes targetServiceId or "*"
     * 3. Otherwise returns false (403 Forbidden).
     */
    public boolean hasPerm(String requiredPerm, String targetServiceId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AdminPrincipal principal)) {
            return false;
        }

        // 1. Bypass check for SUPERADMIN role or wildcard permission
        if (principal.getRoles() != null && principal.getRoles().contains("SUPERADMIN")) {
            return true;
        }

        List<GrantDto> permissions = principal.getPermissions();
        if (permissions == null || permissions.isEmpty()) {
            return false;
        }

        for (GrantDto grant : permissions) {
            // Check wildcard grant
            if ("*".equals(grant.perm())) {
                return true;
            }

            // Check exact permission code match
            if (grant.perm().equalsIgnoreCase(requiredPerm)) {
                List<String> scopes = grant.scope();
                if (scopes == null || scopes.isEmpty()) {
                    return true;
                }
                if (scopes.contains("*") || scopes.contains(targetServiceId)) {
                    return true;
                }
            }
        }

        log.debug("Access denied for principal '{}': required perm '{}' in scope '{}'",
                principal.getUsername(), requiredPerm, targetServiceId);
        return false;
    }

    /**
     * Enforces Maker-Checker dual authorization rule: Checker cannot approve their own requests.
     */
    public boolean isNotCreator(String creatorId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AdminPrincipal principal)) {
            return false;
        }

        if (creatorId == null) {
            return true;
        }

        // Neither admin ID nor username can match creatorId
        return !creatorId.equalsIgnoreCase(principal.getId()) && !creatorId.equalsIgnoreCase(principal.getUsername());
    }
}
