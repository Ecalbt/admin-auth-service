package com.example.adminauth;

import com.example.adminauth.dto.auth.LoginRequest;
import com.example.adminauth.entity.Admin;
import com.example.adminauth.entity.AdminRole;
import com.example.adminauth.entity.AdminStatus;
import com.example.adminauth.entity.Role;
import com.example.adminauth.repository.AdminRepository;
import com.example.adminauth.repository.AdminRoleRepository;
import com.example.adminauth.repository.RoleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminAuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminRepository adminRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private AdminRoleRepository adminRoleRepository;

    @Autowired
    private com.example.adminauth.repository.AuditEventRepository auditEventRepository;

    @Autowired
    private com.example.adminauth.repository.RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private com.example.adminauth.repository.AdminPermissionRepository adminPermissionRepository;

    @Autowired
    private com.example.adminauth.repository.PasswordHistoryRepository passwordHistoryRepository;

    @Autowired
    private com.example.adminauth.repository.BackupCodeRepository backupCodeRepository;

    @Autowired
    private com.example.adminauth.repository.TotpSecretRepository totpSecretRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void initData() {
        auditEventRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        adminPermissionRepository.deleteAll();
        passwordHistoryRepository.deleteAll();
        backupCodeRepository.deleteAll();
        totpSecretRepository.deleteAll();
        adminRoleRepository.deleteAll();
        adminRepository.deleteAll();
        roleRepository.deleteAll();

        Role superRole = roleRepository.save(
                Role.builder().code("SUPERADMIN").name("Super Administrator").tier(1).build()
        );

        Admin superadmin = Admin.builder()
                .id("adm-super-test")
                .username("superadmin")
                .email("superadmin@ocb.com.vn")
                .fullName("Test SuperAdmin")
                .passwordHash(passwordEncoder.encode("SuperAdmin@123456"))
                .status(AdminStatus.ACTIVE)
                .failedLoginAttempts(0)
                .mustChangePassword(false)
                .build();
        adminRepository.save(superadmin);

        adminRoleRepository.save(
                AdminRole.builder().admin(superadmin).role(superRole).assignedBy("SYSTEM").build()
        );
    }

    @Test
    @DisplayName("JWKS discovery endpoint is publicly accessible and returns RSA public keys")
    void testJwksEndpointPublic() throws Exception {
        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys").isArray())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].alg").value("RS256"));
    }

    @Test
    @DisplayName("Unauthorized access to protected endpoint returns 401")
    void testProtectedEndpointWithoutToken() throws Exception {
        mockMvc.perform(get("/v1/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Full End-to-End Auth Flow: Login, Access Protected API, and Logout")
    void testFullAuthFlow() throws Exception {
        // 1. Login
        LoginRequest loginReq = new LoginRequest("superadmin", "SuperAdmin@123456");
        MvcResult loginResult = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andReturn();

        JsonNode root = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = root.path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();

        // 2. Call /v1/me with Bearer token
        mockMvc.perform(get("/v1/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("superadmin"))
                .andExpect(jsonPath("$.data.roles[0]").value("SUPERADMIN"));

        // 3. Call /v1/admins list with Bearer token (Superadmin is allowed)
        mockMvc.perform(get("/v1/admins")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        // 4. Logout
        mockMvc.perform(post("/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 5. Subsequent request with logged-out session should fail (401)
        mockMvc.perform(get("/v1/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GAP-11: Actuator health is public, but other actuator endpoints require authentication")
    void testActuatorEndpointRestrictions() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(200, 503));

        mockMvc.perform(get("/actuator/beans"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("L-13: Login with missing fields triggers 400 Validation Failed")
    void testLoginValidationFailure() throws Exception {
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.details.username").exists());
    }

    @Test
    @DisplayName("SEC-01: SQL injection in login payload safely rejected with 401 (no 500)")
    void testSqlInjectionSafe() throws Exception {
        LoginRequest sqlInjectReq = new LoginRequest("admin' OR '1'='1 --", "Password@123");
        mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(sqlInjectReq)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("X-02: Calling logout without Authorization header returns 401")
    void testLogoutWithoutTokenUnauthorized() throws Exception {
        mockMvc.perform(post("/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("AU-01: Query audit events with SUPERADMIN token succeeds")
    void testQueryAuditEventsSuccess() throws Exception {
        LoginRequest loginReq = new LoginRequest("superadmin", "SuperAdmin@123456");
        MvcResult loginResult = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = root.path("data").path("accessToken").asText();

        mockMvc.perform(get("/v1/audit-events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    @Test
    @DisplayName("SEC-07: Audit endpoints are append-only; DELETE on /v1/audit-events returns 405")
    void testAuditEndpointsAppendOnly() throws Exception {
        LoginRequest loginReq = new LoginRequest("superadmin", "SuperAdmin@123456");
        MvcResult loginResult = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = root.path("data").path("accessToken").asText();

        mockMvc.perform(delete("/v1/audit-events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("L-14: Audit records client IP from X-Forwarded-For header")
    void testAuditCapturesXForwardedForIp() throws Exception {
        LoginRequest loginReq = new LoginRequest("superadmin", "SuperAdmin@123456");
        mockMvc.perform(post("/v1/auth/login")
                        .header("X-Forwarded-For", "10.0.0.5, 172.16.0.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk());

        var events = auditEventRepository.findAll();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).getIpAddress()).isEqualTo("10.0.0.5");
    }

    @Test
    @DisplayName("A-08 & SEC-08: Admin details retrieved; sensitive secrets never exposed in responses")
    void testAdminDetailAndNoSecretLeakage() throws Exception {
        LoginRequest loginReq = new LoginRequest("superadmin", "SuperAdmin@123456");
        MvcResult loginResult = mockMvc.perform(post("/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginReq)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String token = root.path("data").path("accessToken").asText();

        // 1. GET /v1/admins/{id} for existing admin
        mockMvc.perform(get("/v1/admins/adm-super-test")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("superadmin"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.totpSecret").doesNotExist());

        // 2. GET /v1/admins/{id} for non-existent admin -> 404
        mockMvc.perform(get("/v1/admins/non-existent-id")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Not Found"));

        // 3. GET /v1/me does not leak password hash or totp secret
        mockMvc.perform(get("/v1/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.data.totpSecret").doesNotExist());
    }
}
