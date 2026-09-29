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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
public class JwtKeyPairManager {

    @Value("${app.jwt.key-id:ocb-admin-auth-key-1}")
    private String keyId;

    @Value("${app.jwt.keys-dir:}")
    private String keysDir;

    @Value("${app.jwt.private-key-file:}")
    private String privateKeyFile;

    @Value("${app.jwt.public-key-file:}")
    private String publicKeyFile;

    @Getter
    private RSAPrivateKey privateKey;

    @Getter
    private RSAPublicKey publicKey;

    private RSAKey rsaJwk;

    @PostConstruct
    public void init() {
        try {
            log.info("Initializing RSA 2048-bit KeyPair with keyId: {}", keyId);

            KeyPair keyPair = null;

            // 1. Try loading from configured explicit private/public key files
            if (privateKeyFile != null && !privateKeyFile.isBlank()
                    && publicKeyFile != null && !publicKeyFile.isBlank()) {
                keyPair = loadKeyPairFromFiles(Paths.get(privateKeyFile), Paths.get(publicKeyFile));
                if (keyPair != null) {
                    log.info("Loaded RSA KeyPair from configured key files: {}, {}", privateKeyFile, publicKeyFile);
                }
            }

            // 2. Try loading from or persisting to keysDir
            if (keyPair == null && keysDir != null && !keysDir.isBlank()) {
                Path dir = Paths.get(keysDir);
                Path privPath = dir.resolve("jwt_private_key.der");
                Path pubPath = dir.resolve("jwt_public_key.der");

                if (Files.exists(privPath) && Files.exists(pubPath)) {
                    keyPair = loadKeyPairFromFiles(privPath, pubPath);
                    if (keyPair != null) {
                        log.info("Loaded persistent RSA KeyPair from directory: {}", dir.toAbsolutePath());
                    }
                } else {
                    keyPair = generateNewKeyPair();
                    try {
                        Files.createDirectories(dir);
                        Files.write(privPath, keyPair.getPrivate().getEncoded());
                        Files.write(pubPath, keyPair.getPublic().getEncoded());
                        log.info("Persisted new RSA KeyPair to directory: {}", dir.toAbsolutePath());
                    } catch (IOException e) {
                        log.warn("Could not persist RSA KeyPair to {}: {}", dir, e.getMessage());
                    }
                }
            }

            // 3. Fallback to in-memory generation if not loaded or persisted
            if (keyPair == null) {
                keyPair = generateNewKeyPair();
                log.warn("No persistent RSA key directory or files configured. Generated ephemeral in-memory KeyPair.");
            }

            this.publicKey = (RSAPublicKey) keyPair.getPublic();
            this.privateKey = (RSAPrivateKey) keyPair.getPrivate();

            this.rsaJwk = new RSAKey.Builder(this.publicKey)
                    .privateKey(this.privateKey)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .keyID(this.keyId)
                    .build();

            log.info("RSA KeyPair successfully initialized.");
        } catch (Exception e) {
            log.error("Failed to initialize RSA key pair", e);
            throw new IllegalStateException("Cannot initialize RSA KeyPair", e);
        }
    }

    private KeyPair generateNewKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }

    private KeyPair loadKeyPairFromFiles(Path privPath, Path pubPath) {
        try {
            byte[] privBytes = readKeyBytes(privPath);
            byte[] pubBytes = readKeyBytes(pubPath);

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privKey = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            RSAPublicKey pubKey = (RSAPublicKey) keyFactory.generatePublic(new X509EncodedKeySpec(pubBytes));
            return new KeyPair(pubKey, privKey);
        } catch (Exception e) {
            log.error("Failed to load KeyPair from files {} and {}: {}", privPath, pubPath, e.getMessage());
            return null;
        }
    }

    private byte[] readKeyBytes(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String content = new String(bytes).trim();
        if (content.contains("-----BEGIN")) {
            String clean = content
                    .replaceAll("-----BEGIN [A-Z ]+-----", "")
                    .replaceAll("-----END [A-Z ]+-----", "")
                    .replaceAll("\\s+", "");
            return Base64.getDecoder().decode(clean);
        }
        return bytes;
    }

    public Map<String, Object> getJwksJsonObject() {
        JWKSet jwkSet = new JWKSet(this.rsaJwk.toPublicJWK());
        return jwkSet.toJSONObject();
    }

    public String getKeyId() {
        return keyId;
    }
}
