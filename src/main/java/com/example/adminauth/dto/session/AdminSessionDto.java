package com.example.adminauth.dto.session;

import java.io.Serializable;
import java.time.Instant;

public record AdminSessionDto(
        String sessionId,
        String adminId,
        String username,
        String ipAddress,
        String userAgent,
        Instant createdAt,
        Instant lastUsedAt,
        Instant expiresAt
) implements Serializable {}
