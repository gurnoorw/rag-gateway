package dev.raggateway.api;

/** Header names used by the gateway. One place, so tests and docs agree. */
public final class GatewayHeaders {

    /** Response: request id, also in the log row and app log lines (G8). */
    public static final String REQUEST_ID = "X-Request-Id";
    /** Response: provider that served the answer (t03, AC-t03-8). */
    public static final String PROVIDER = "X-Gateway-Provider";
    /** Response: model that served the answer. */
    public static final String MODEL = "X-Gateway-Model";
    /** Response: {@code HIT}, {@code MISS}, {@code BYPASS} or {@code NOT_ELIGIBLE} (t05). */
    public static final String CACHE = "X-Gateway-Cache";
    /** Response: routing rule that chose the model when the caller sent {@code auto} (t06). */
    public static final String ROUTE_RULE = "X-Gateway-Route-Rule";
    /** Request: {@code true} skips cache read and write for this request (t05). */
    public static final String CACHE_BYPASS = "X-Gateway-Cache-Bypass";
    /** Response: standard header on 429 {@code rate_limited}. */
    public static final String RETRY_AFTER = "Retry-After";

    private GatewayHeaders() {
    }
}
