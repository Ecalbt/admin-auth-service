package com.example.adminauth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cấu hình OpenAPI 3.0 (Swagger UI).
 * Tích hợp chuẩn JWT Bearer Authentication (RS256) giúp hiển thị nút "Authorize" trên UI.
 */
@Configuration
public class OpenApiConfig {

    public static final String SECURITY_SCHEME_NAME = "BearerAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("OCB Auto-Earning — Admin Auth Service")
                        .version("1.0.0")
                        .description("Microservice trung tâm Xác thực (Authentication), Phân quyền Hybrid (Tier/Preset + Grant Scope), "
                                + "Quản lý phiên đăng nhập Server-side (Redis) và Truy vết Kiểm toán (Audit Trail) cho Admin Portal.")
                        .contact(new Contact().name("OCB Auto-Earning IT Team").email("it-autoeaning@ocb.com.vn"))
                        .license(new License().name("OCB Internal Enterprise License")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME,
                                new SecurityScheme()
                                        .name(SECURITY_SCHEME_NAME)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Nhập JWT Access Token nhận được từ API /api/v1/auth/login (không cần gõ tiền tố 'Bearer ')")));
    }
}
