package dev.raggateway.error;

import java.util.Map;

/**
 * 429 {@code rate_limited}: the tenant's token bucket cannot cover the reservation now (t02).
 * Carries {@code Retry-After} in whole seconds, rounded up, at least 1 (AC-t02-3).
 */
public final class RateLimitedException extends GatewayException {

    private final long retryAfterMs;

    public RateLimitedException(long retryAfterMs) {
        super(ErrorCode.RATE_LIMITED, "token rate limit exceeded for this tenant");
        this.retryAfterMs = retryAfterMs;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }

    /** Whole seconds, rounded up, minimum 1. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfterMs + 999) / 1000);
    }

    @Override
    public Map<String, String> responseHeaders() {
        return Map.of("Retry-After", Long.toString(retryAfterSeconds()));
    }
}
