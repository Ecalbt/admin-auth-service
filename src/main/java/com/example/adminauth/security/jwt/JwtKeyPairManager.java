package com.example.adminauth.security.jwt;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Map;

@Slf4j
@Component
public class JwtKeyPairManager {

    @Value("${app.jwt.key-id:ocb-admin-auth-key-1}")
    private String keyId;

    @Getter
    private RSAPrivateKey privateKey;

    @Getter
    private RSAPublicKey publicKey;

    private RSAKey rsaJwk;

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing RSA 2048-bit KeyPair with keyId: {}", keyId);
            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            this.publicKey = (RSAPublicKey) keyPair.getPublic();
            this.privateKey = (RSAPrivateKey) keyPair.getPrivate();

            this.rsaJwk = new RSAKey.Builder(this.publicKey)
                    .privateKey(this.privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(this.keyId)
                    .build();

            log.info("RSA KeyPair successfully initialized.");
        } catch (NoSuchAlgorithmException e) {
            log.error("Failed to generate RSA key pair", e);
            throw new IllegalStateException("Cannot initialize RSA KeyPair", e);
        }
    }

    public Map<String, Object> getJwksJsonObject() {
        JWKSet jwkSet = new JWKSet(this.rsaJwk.toPublicJWK());
        return jwkSet.toJSONObject();
    }

    public String getKeyId() {
        return keyId;
    }
}
