package com.example.adminauth.service;

import com.example.adminauth.dto.auth.ChangePasswordRequest;
import com.example.adminauth.dto.auth.CompleteOnboardingRequest;
import com.example.adminauth.dto.auth.LoginRequest;
import com.example.adminauth.dto.auth.LoginResponse;
import com.example.adminauth.dto.auth.MfaVerifyRequest;
import com.example.adminauth.dto.auth.RefreshTokenRequest;
import com.example.adminauth.dto.mfa.TotpSetupResponse;
import com.example.adminauth.security.AdminPrincipal;

/**
 * Service Interface định nghĩa các quy trình Xác thực, Cấp & Xoay vòng Token, Quản lý Mật khẩu.
 */
public interface AuthService {

    LoginResponse login(LoginRequest req, String ipAddress, String userAgent);

    LoginResponse verifyMfa(MfaVerifyRequest req, String ipAddress, String userAgent);

    TotpSetupResponse setupOnboardingMfa(String onboardingToken);

    LoginResponse completeOnboarding(CompleteOnboardingRequest req, String ipAddress, String userAgent);

    LoginResponse refreshToken(RefreshTokenRequest req, String ipAddress, String userAgent);

    void logout(String sessionId, AdminPrincipal actor);

    void changePassword(AdminPrincipal actor, ChangePasswordRequest req);
}
