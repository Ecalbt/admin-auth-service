package com.example.adminauth.dto.auth;

import com.example.adminauth.security.jwt.GrantDto;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;

import java.util.List;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(
        boolean mfaRequired,
        String mfaToken,
        String accessToken,
        String refreshToken,
        Long expiresIn,
        String adminId,
        String username,
        String fullName,
        Boolean mustChangePassword,
        List<String> roles,
        List<GrantDto> permissions
) {}
