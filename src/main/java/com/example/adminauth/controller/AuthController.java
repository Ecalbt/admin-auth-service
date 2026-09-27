package com.example.adminauth.controller;

import com.example.adminauth.common.ApiResponse;
import com.example.adminauth.dto.auth.LoginRequest;
import com.example.adminauth.dto.auth.LoginResponse;
import com.example.adminauth.dto.auth.MfaVerifyRequest;
import com.example.adminauth.dto.auth.RefreshTokenRequest;
import com.example.adminauth.security.AdminPrincipal;
import com.example.adminauth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Public Authentication Endpoints")
@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Login Step 1: Submit credentials to receive tokens or MFA challenge")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String ip = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ApiResponse.ok("Login evaluated", authService.login(req, ip, userAgent));
    }

    @Operation(summary = "Login Step 2: Verify MFA code (TOTP or Backup code)")
    @PostMapping("/mfa/verify")
    public ApiResponse<LoginResponse> verifyMfa(@Valid @RequestBody MfaVerifyRequest req, HttpServletRequest request) {
        String ip = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ApiResponse.ok("MFA verification successful", authService.verifyMfa(req, ip, userAgent));
    }

    @Operation(summary = "Rotate refresh token and issue new access token")
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest req, HttpServletRequest request) {
        String ip = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ApiResponse.ok("Token refreshed successfully", authService.refreshToken(req, ip, userAgent));
    }

    @Operation(summary = "Logout and terminate current session")
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AdminPrincipal principal) {
        if (principal != null) {
            authService.logout(principal.getSessionId(), principal);
        }
        return ApiResponse.ok("Logged out successfully");
    }

    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        } else if (ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
