package dev.raggateway.core;

/**
 * Cheap, local token estimate used where the provider has not reported usage yet: the rate-limit
 * reservation (t02), usage of a cancelled or failed stream (t01), chunk sizing (t09). It is an
 * approximation by design (DECISIONS.md D-11); billing always prefers provider-reported usage.
 * Implementations must over-estimate rather than under-estimate English text.
 */
public interface TokenEstimator {

    /** Estimated input tokens of the whole request (all messages plus per-message overhead). */
    int estimateInput(ChatRequest request);

    /** Estimated tokens of one piece of text. */
    int estimate(String text);
}
