package dev.raggateway.core;

import java.util.Objects;

/**
 * One call to one provider, recorded in {@link RequestContext} by the terminal (t01 direct call,
 * t03 router, t07 validation retry). Billing and the request log sum over all attempts, so the
 * tokens of a failed validation attempt are charged (DECISIONS.md C8).
 *
 * @param provider        provider name from config, e.g. {@code claude}
 * @param model           public model name sent to that provider
 * @param fallback        true when this provider is not the one model selection chose (C21)
 * @param outcome         how it ended
 * @param usage           usage reported by the provider, or estimated (see {@code usageEstimated})
 * @param usageEstimated  true when the provider never reported usage (failure or cancel mid-stream);
 *                        the estimate comes from {@link TokenEstimator}
 * @param durationNanos   wall time of the attempt
 * @param firstTokenNanos time from attempt start to the first token, or -1 if none
 * @param errorKind       short error classifier for logs (e.g. {@code FIRST_TOKEN_TIMEOUT}), or null
 */
public record Attempt(String provider, String model, boolean fallback, AttemptOutcome outcome,
                      Usage usage, boolean usageEstimated, long durationNanos, long firstTokenNanos,
                      String errorKind) {
    public Attempt {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(usage, "usage");
    }
}
