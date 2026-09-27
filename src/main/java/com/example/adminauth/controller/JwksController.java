package com.example.adminauth.controller;

import com.example.adminauth.security.jwt.JwtKeyPairManager;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "JWKS", description = "Public Key Discovery Endpoint")
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final JwtKeyPairManager keyPairManager;

    @Operation(summary = "Expose RSA Public Keys in JWK Set format for microservices token verification")
    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> getJwks() {
        return keyPairManager.getJwksJsonObject();
    }
}
