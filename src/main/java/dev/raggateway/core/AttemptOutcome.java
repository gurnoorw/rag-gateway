package dev.raggateway.core;

/** How one provider attempt ended. Recorded per attempt so failed attempts are billed (t07) and logged. */
public enum AttemptOutcome {
    /** Stream completed and was accepted. */
    SUCCEEDED,
    /** Failed before any token reached the client: retry or fallback is legal. */
    FAILED_BEFORE_FIRST_TOKEN,
    /** Failed after at least one token reached the client: no retry, SSE error event. */
    FAILED_MID_STREAM,
    /** The client went away; the upstream call was cancelled. */
    CLIENT_CANCELLED,
    /** Completed, but the output failed JSON-schema or structural validation (t07). */
    INVALID_OUTPUT
}
