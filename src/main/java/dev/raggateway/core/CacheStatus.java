package dev.raggateway.core;

/** Cache result for one request (t05). Shown in the {@code X-Gateway-Cache} header and the log row. */
public enum CacheStatus {
    /** Not cacheable: temperature absent or above 0, or caching disabled. */
    NOT_ELIGIBLE,
    /** Caller sent the bypass header: no read, no write. */
    BYPASS,
    HIT,
    MISS
}
