package com.example.adminauth.service;

import com.example.adminauth.dto.auth.*;
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

    ForgotPasswordResponse forgotPassword(ForgotPasswordRequest req, String ipAddress, String userAgent);

    void resetPassword(ResetPasswordRequest req, String ipAddress, String userAgent);
}
