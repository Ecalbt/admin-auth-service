package com.example.adminauth.security.jwt;

import com.example.adminauth.security.AdminPrincipal;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    private final JwtKeyPairManager keyPairManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.jwt.issuer:https://auth.autoeaning.ocb.com.vn}")
    private String issuer;

    @Value("${app.jwt.access-token-expiration-seconds:1800}")
    private long accessTokenExpirationSeconds;

    @Value("${app.jwt.refresh-token-expiration-seconds:86400}")
    private long refreshTokenExpirationSeconds;

    public String generateAccessToken(String adminId, String username, String email, String sessionId,
                                      List<String> roles, List<GrantDto> permissions, boolean mfaVerified) {
        try {
            JWSSigner signer = new RSASSASigner(keyPairManager.getPrivateKey());

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(keyPairManager.getKeyId())
                    .type(JOSEObjectType.JWT)
                    .build();

            Date now = new Date();
            Date validity = new Date(now.getTime() + (accessTokenExpirationSeconds * 1000));

            // Convert GrantDto to List of Maps for JWT JSON serialization
            List<Map<String, Object>> permList = new ArrayList<>();
            if (permissions != null) {
                for (GrantDto g : permissions) {
                    Map<String, Object> map = new HashMap<>();
                    map.put("perm", g.perm());
                    map.put("scope", g.scope());
                    permList.add(map);
                }
            }

            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(adminId)
                    .issueTime(now)
                    .expirationTime(validity)
                    .jwtID(UUID.randomUUID().toString())
                    .claim("username", username)
                    .claim("email", email)
                    .claim("sid", sessionId)
                    .claim("roles", roles)
                    .claim("permissions", permList)
                    .claim("mfa_verified", mfaVerified)
                    .build();

            SignedJWT signedJWT = new SignedJWT(header, claimsSet);
            signedJWT.sign(signer);

            return signedJWT.serialize();
        } catch (JOSEException e) {
            log.error("Failed to sign access token", e);
            throw new RuntimeException("Error signing access token", e);
        }
    }

    public boolean validateToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWSVerifier verifier = new RSASSAVerifier(keyPairManager.getPublicKey());

            if (!signedJWT.verify(verifier)) {
                log.warn("Invalid JWT signature");
                return false;
            }

            Date expiration = signedJWT.getJWTClaimsSet().getExpirationTime();
            if (expiration == null || new Date().after(expiration)) {
                log.warn("JWT token has expired");
                return false;
            }

            return true;
        } catch (ParseException | JOSEException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    public AdminPrincipal getPrincipalFromToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            JWTClaimsSet claims = signedJWT.getJWTClaimsSet();

            String adminId = claims.getSubject();
            String username = claims.getStringClaim("username");
            String email = claims.getStringClaim("email");
            String sid = claims.getStringClaim("sid");
            Boolean mfaVerified = claims.getBooleanClaim("mfa_verified");

            List<String> roles = claims.getStringListClaim("roles");
            if (roles == null) roles = Collections.emptyList();

            List<GrantDto> permissions = new ArrayList<>();
            Object permsObj = claims.getClaim("permissions");
            if (permsObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        String perm = (String) map.get("perm");
                        Object scopeObj = map.get("scope");
                        List<String> scopeList = new ArrayList<>();
                        if (scopeObj instanceof List<?> sl) {
                            for (Object o : sl) {
                                scopeList.add(String.valueOf(o));
                            }
                        }
                        permissions.add(new GrantDto(perm, scopeList));
                    }
                }
            }

            return AdminPrincipal.builder()
                    .id(adminId)
                    .username(username)
                    .email(email)
                    .sessionId(sid)
                    .mfaVerified(mfaVerified != null && mfaVerified)
                    .roles(roles)
                    .permissions(permissions)
                    .build();
        } catch (ParseException e) {
            log.error("Error parsing claims from token", e);
            throw new RuntimeException("Cannot parse claims from token", e);
        }
    }

    public String extractSessionId(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            return signedJWT.getJWTClaimsSet().getStringClaim("sid");
        } catch (ParseException e) {
            return null;
        }
    }

    public String generateSecureRandomToken() {
        byte[] randomBytes = new byte[32];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedhash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder(2 * encodedhash.length);
            for (byte b : encodedhash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpirationSeconds;
    }

    public long getRefreshTokenExpirationSeconds() {
        return refreshTokenExpirationSeconds;
    }
}
