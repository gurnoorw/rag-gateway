package dev.raggateway.error;

/**
 * Client-visible error codes. {@link #code()} goes into {@code error.code} of the body; {@link #type()}
 * into {@code error.type}, using OpenAI's type names so OpenAI SDKs raise the matching exception class.
 */
public enum ErrorCode {
    INVALID_REQUEST(400, "invalid_request_error"),
    /** The provider rejected the request as invalid (non-retryable 4xx). No retry, no fallback (t03). */
    UPSTREAM_REJECTED(400, "invalid_request_error"),
    UNAUTHORIZED(401, "authentication_error"),
    FORBIDDEN(403, "permission_error"),
    MODEL_NOT_ALLOWED(403, "permission_error"),
    NOT_FOUND(404, "not_found_error"),
    PAYLOAD_TOO_LARGE(413, "invalid_request_error"),
    RATE_LIMITED(429, "rate_limit_error"),
    BUDGET_EXCEEDED(429, "insufficient_quota"),
    INTERNAL_ERROR(500, "api_error"),
    PROVIDER_ERROR(502, "api_error"),
    INVALID_MODEL_OUTPUT(502, "api_error"),
    ALL_PROVIDERS_FAILED(503, "api_error"),
    /**
     * A dependency this request needs is unreachable or out of capacity for now: the database during RAG retrieval
     * (no connection, pool timeout, query timeout) or the embedding model in {@code vector} mode. Retryable; not a
     * chat provider failure (that is {@link #ALL_PROVIDERS_FAILED}). t10 review, D-50.
     */
    SERVICE_UNAVAILABLE(503, "api_error"),
    /** This instance is at its in-flight request limit (load shedding, D-46); retry shortly or on another instance. */
    OVERLOADED(503, "api_error");

    private final int httpStatus;
    private final String type;

    ErrorCode(int httpStatus, String type) {
        this.httpStatus = httpStatus;
        this.type = type;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String type() {
        return type;
    }

    /** Machine-readable code, e.g. {@code rate_limited}. */
    public String code() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
