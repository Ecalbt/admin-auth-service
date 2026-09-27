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
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void initData() {
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
}
