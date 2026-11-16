package dev.raggateway.auth;

import dev.raggateway.core.ApiKeyPrincipal;

import java.util.Optional;

/**
 * Resolves a presented API key to its caller. Implementation (t01): SHA-256 hex of the key, lookup by
 * hash in Postgres, revoked keys ignored, results cached in memory for a short TTL (D-23).
 * The presented key must never be logged, stored, or put in an exception message (AC-t01-8).
 */
public interface ApiKeyAuthenticator {

    /** Empty when the key is unknown or revoked. */
    Optional<ApiKeyPrincipal> authenticate(String presentedKey);
}
