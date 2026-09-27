package com.example.adminauth.security;

import com.example.adminauth.security.jwt.GrantDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CustomAuthEvaluatorTest {

    private final CustomAuthEvaluator evaluator = new CustomAuthEvaluator();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("SUPERADMIN role automatically bypasses all permission checks")
    void testSuperAdminBypass() {
        AdminPrincipal principal = AdminPrincipal.builder()
                .id("adm-super")
                .username("superadmin")
                .roles(List.of("SUPERADMIN"))
                .permissions(List.of())
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        assertThat(evaluator.hasPerm("config:write", "system-params-api")).isTrue();
        assertThat(evaluator.hasPerm("random:permission", "any-scope")).isTrue();
    }

    @Test
    @DisplayName("Regular admin with exact permission and matching scope is authorized")
    void testServiceAdminAuthorizedForCorrectScope() {
        AdminPrincipal principal = AdminPrincipal.builder()
                .id("adm-maker")
                .username("maker_user")
                .roles(List.of("SERVICE_ADMIN"))
                .permissions(List.of(
                        new GrantDto("config:write", List.of("system-params-api")),
                        new GrantDto("config:read", List.of("system-params-api"))
                ))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        // Allowed on correct service
        assertThat(evaluator.hasPerm("config:write", "system-params-api")).isTrue();
        assertThat(evaluator.hasPerm("config:read", "system-params-api")).isTrue();

        // Forbidden on different service
        assertThat(evaluator.hasPerm("config:write", "core-cd-adapter")).isFalse();

        // Forbidden for ungranted action (e.g. approve)
        assertThat(evaluator.hasPerm("config:approve", "system-params-api")).isFalse();
    }

    @Test
    @DisplayName("Maker-Checker constraint: Checker cannot approve their own requests")
    void testMakerCheckerNotCreator() {
        AdminPrincipal principal = AdminPrincipal.builder()
                .id("adm-checker-01")
                .username("checker_user")
                .roles(List.of("SERVICE_ADMIN"))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities())
        );

        // Created by someone else -> can approve
        assertThat(evaluator.isNotCreator("adm-maker-01")).isTrue();
        assertThat(evaluator.isNotCreator("maker_user")).isTrue();

        // Created by oneself (id or username match) -> cannot approve
        assertThat(evaluator.isNotCreator("adm-checker-01")).isFalse();
        assertThat(evaluator.isNotCreator("checker_user")).isFalse();
    }
}
