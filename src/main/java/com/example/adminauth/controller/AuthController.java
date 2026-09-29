package com.example.adminauth.controller;

import com.example.adminauth.common.ApiResponse;
import com.example.adminauth.dto.auth.*;
import com.example.adminauth.dto.mfa.TotpSetupResponse;
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

    @Operation(summary = "Login Step 1: Submit credentials to receive tokens, MFA challenge, or Onboarding challenge")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String ip = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ApiResponse.ok("Login evaluated", authService.login(req, ip, userAgent));
    }

    @Operation(summary = "Onboarding Step 1: Initialize/Retrieve TOTP setup using onboarding token")
    @PostMapping("/onboarding/mfa/setup")
    public ApiResponse<TotpSetupResponse> setupOnboardingMfa(@Valid @RequestBody OnboardingMfaSetupRequest req) {
        return ApiResponse.ok("TOTP setup initialized for onboarding", authService.setupOnboardingMfa(req.onboardingToken()));
    }

    @Operation(summary = "Onboarding Step 2: Complete onboarding by changing password and verifying first TOTP code")
    @PostMapping("/onboarding/complete")
    public ApiResponse<LoginResponse> completeOnboarding(@Valid @RequestBody CompleteOnboardingRequest req, HttpServletRequest request) {
        String ip = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ApiResponse.ok("Onboarding completed successfully. Account is now active with MFA enabled.",
                authService.completeOnboarding(req, ip, userAgent));
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

    @Operation(summary = "Forgot Password Step 1: Request password reset via TOTP verification")
    @PostMapping("/password/forgot")
    public ApiResponse<ForgotPasswordResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest req, HttpServletRequest request) {
        String ip = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        return ApiResponse.ok("Reset token issued", authService.forgotPassword(req, ip, userAgent));
    }

    @Operation(summary = "Forgot Password Step 2: Submit reset token, TOTP code, and new password")
    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest req, HttpServletRequest request) {
        String ip = extractClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        authService.resetPassword(req, ip, userAgent);
        return ApiResponse.ok("Password reset successfully. Please login with your new password.");
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
