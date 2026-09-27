package com.example.adminauth.controller;

import com.example.adminauth.common.ApiResponse;
import com.example.adminauth.dto.admin.AdminDetailDto;
import com.example.adminauth.dto.auth.ChangePasswordRequest;
import com.example.adminauth.dto.mfa.BackupCodesResponse;
import com.example.adminauth.dto.mfa.TotpSetupResponse;
import com.example.adminauth.dto.mfa.TotpVerifyCodeRequest;
import com.example.adminauth.dto.session.AdminSessionDto;
import com.example.adminauth.security.AdminPrincipal;
import com.example.adminauth.service.AdminManagementService;
import com.example.adminauth.service.AuthService;
import com.example.adminauth.service.MfaService;
import com.example.adminauth.service.SessionManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Self-Service", description = "Current Authenticated Admin Profile & Session Operations")
@RestController
@RequestMapping("/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final AdminManagementService adminManagementService;
    private final AuthService authService;
    private final MfaService mfaService;
    private final SessionManagementService sessionManagementService;

    @Operation(summary = "Get current admin profile, roles and granted permissions")
    @GetMapping
    public ApiResponse<AdminDetailDto> getMyProfile(@AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.ok(adminManagementService.getAdminDetail(principal.getId()));
    }

    @Operation(summary = "Change own password (enforces complexity & history rules)")
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(
            @AuthenticationPrincipal AdminPrincipal principal,
            @Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(principal, req);
        return ApiResponse.ok("Password changed successfully");
    }

    @Operation(summary = "Initiate TOTP MFA setup (returns secret & QR code URI)")
    @PostMapping("/mfa/totp/setup")
    public ApiResponse<TotpSetupResponse> setupTotp(@AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.ok("MFA setup initialized", mfaService.setupTotp(principal.getId()));
    }

    @Operation(summary = "Confirm & activate TOTP with verification code (returns 10 backup codes)")
    @PostMapping("/mfa/totp/enable")
    public ApiResponse<BackupCodesResponse> enableTotp(
            @AuthenticationPrincipal AdminPrincipal principal,
            @Valid @RequestBody TotpVerifyCodeRequest req) {
        int code = Integer.parseInt(req.code().trim());
        return ApiResponse.ok("MFA enabled successfully", mfaService.confirmTotp(principal.getId(), code));
    }

    @Operation(summary = "List all active sessions of current admin")
    @GetMapping("/sessions")
    public ApiResponse<List<AdminSessionDto>> getMySessions(@AuthenticationPrincipal AdminPrincipal principal) {
        return ApiResponse.ok(sessionManagementService.getMySessions(principal));
    }

    @Operation(summary = "Revoke / kick a specific session of current admin")
    @DeleteMapping("/sessions/{sid}")
    public ApiResponse<Void> revokeMySession(
            @PathVariable String sid,
            @AuthenticationPrincipal AdminPrincipal principal) {
        sessionManagementService.revokeSession(principal.getId(), sid, principal);
        return ApiResponse.ok("Session revoked successfully");
    }

    @Operation(summary = "Revoke all sessions of current admin")
    @DeleteMapping("/sessions")
    public ApiResponse<Void> revokeAllMySessions(@AuthenticationPrincipal AdminPrincipal principal) {
        sessionManagementService.revokeAllSessions(principal.getId(), principal);
        return ApiResponse.ok("All sessions revoked successfully");
    }
}
