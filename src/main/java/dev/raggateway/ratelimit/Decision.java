package dev.raggateway.ratelimit;

/** Result of a reservation attempt (LLD). {@code retryAfterMs} is 0 when allowed. */
public record Decision(boolean allowed, long retryAfterMs) {

    public static Decision allow() {
        return new Decision(true, 0);
    }

    public static Decision deny(long waitMs) {
        return new Decision(false, waitMs);
    }
}
