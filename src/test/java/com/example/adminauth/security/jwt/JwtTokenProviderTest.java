package com.example.adminauth.security.jwt;

import com.example.adminauth.security.AdminPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtKeyPairManager keyPairManager;
    private JwtTokenProvider tokenProvider;

    @BeforeEach
    void setUp() {
        keyPairManager = new JwtKeyPairManager();
        ReflectionTestUtils.setField(keyPairManager, "keyId", "test-key-1");
        keyPairManager.init();

        tokenProvider = new JwtTokenProvider(keyPairManager);
        ReflectionTestUtils.setField(tokenProvider, "issuer", "https://auth.test.ocb.com.vn");
        ReflectionTestUtils.setField(tokenProvider, "accessTokenExpirationSeconds", 1800L);
        ReflectionTestUtils.setField(tokenProvider, "refreshTokenExpirationSeconds", 86400L);
    }

    @Test
    @DisplayName("Generate access token with RS256 signature and verify validity")
    void testGenerateAndValidateToken() {
        GrantDto grant = new GrantDto("config:write", List.of("system-params-api"));
        String token = tokenProvider.generateAccessToken(
                "adm-1", "maker_user", "maker@ocb.com.vn", "sess-123",
                List.of("SERVICE_ADMIN"), List.of(grant), true
        );

        assertThat(token).isNotBlank();
        assertThat(tokenProvider.validateToken(token)).isTrue();

        AdminPrincipal principal = tokenProvider.getPrincipalFromToken(token);
        assertThat(principal.getId()).isEqualTo("adm-1");
        assertThat(principal.getUsername()).isEqualTo("maker_user");
        assertThat(principal.getSessionId()).isEqualTo("sess-123");
        assertThat(principal.isMfaVerified()).isTrue();
        assertThat(principal.getRoles()).containsExactly("SERVICE_ADMIN");
        assertThat(principal.getPermissions()).hasSize(1);
        assertThat(principal.getPermissions().get(0).perm()).isEqualTo("config:write");
        assertThat(principal.getPermissions().get(0).scope()).containsExactly("system-params-api");
    }

    @Test
    @DisplayName("SuperAdmin wildcard permission token generation")
    void testSuperAdminWildcardToken() {
        GrantDto wildcardGrant = new GrantDto("*", List.of("*"));
        String token = tokenProvider.generateAccessToken(
                "adm-super", "superadmin", "superadmin@ocb.com.vn", "sess-root",
                List.of("SUPERADMIN"), List.of(wildcardGrant), true
        );

        assertThat(tokenProvider.validateToken(token)).isTrue();
        AdminPrincipal principal = tokenProvider.getPrincipalFromToken(token);
        assertThat(principal.getRoles()).contains("SUPERADMIN");
        assertThat(principal.getPermissions().get(0).perm()).isEqualTo("*");
        assertThat(principal.getPermissions().get(0).scope()).contains("*");
    }

    @Test
    @DisplayName("Extract session ID from token")
    void testExtractSessionId() {
        String token = tokenProvider.generateAccessToken(
                "adm-1", "user1", "user1@ocb.com.vn", "session-xyz-999",
                List.of("AUDITOR"), List.of(), false
        );

        assertThat(tokenProvider.extractSessionId(token)).isEqualTo("session-xyz-999");
    }
}
