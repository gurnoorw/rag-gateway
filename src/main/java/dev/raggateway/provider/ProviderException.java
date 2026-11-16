package dev.raggateway.provider;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A provider call failed (LLD, extended). Checked, so the router must handle it. {@code retryable} is
 * true for timeouts, connection errors, 429 and 5xx; false for other 4xx, which also do not trigger
 * fallback (t03, AC-t03-6).
 */
public final class ProviderException extends Exception {

    /** What went wrong; used in logs and the {@code errorKind} of an attempt. */
    public enum Kind {
        CONNECT_TIMEOUT, FIRST_TOKEN_TIMEOUT, IDLE_TIMEOUT, CONNECTION, HTTP_STATUS, MALFORMED_RESPONSE
    }

    private final Kind kind;
    private final boolean retryable;
    private final int httpStatus;
    private final Duration retryAfter;

    /**
     * @param kind       failure class
     * @param message    short description; no prompt text, no keys
     * @param retryable  whether retry/fallback is allowed (before the first token only)
     * @param httpStatus upstream HTTP status, or 0 if there was none
     * @param retryAfter upstream {@code Retry-After}, or null (honoured up to a cap, G9)
     * @param cause      underlying exception, or null
     */
    public ProviderException(Kind kind, String message, boolean retryable, int httpStatus,
                             Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public boolean retryable() {
        return retryable;
    }

    /** Upstream HTTP status, or 0. */
    public int httpStatus() {
        return httpStatus;
    }

    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Whether the router may move on to the next provider in the chain (t03, DECISIONS.md D-30): every
     * retryable failure, plus an upstream 401 or 403, which means this provider's credential is missing or
     * lacks access. That is not retried on the same provider (it would fail the same way) but another
     * provider can still answer. Any other 4xx means the request itself is invalid: no fallback (AC-t03-6).
     */
    public boolean fallbackAllowed() {
        return retryable || httpStatus == 401 || httpStatus == 403;
    }
}
