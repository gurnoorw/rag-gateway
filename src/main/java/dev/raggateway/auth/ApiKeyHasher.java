package dev.raggateway.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hex of an API key (DECISIONS.md D-22). Keys are 32 random bytes, so a fast unsalted hash is
 * enough against brute force and allows an indexed lookup by hash. The result is what {@code api_keys.key_hash}
 * stores; the plaintext is never stored or logged.
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {
    }

    /** Lower-case hex SHA-256 of the key's UTF-8 bytes (64 characters). */
    public static String sha256Hex(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform must provide SHA-256.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
