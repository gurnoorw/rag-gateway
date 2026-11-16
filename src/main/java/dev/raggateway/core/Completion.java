package dev.raggateway.core;

import java.util.Objects;

/**
 * End-of-stream summary handed to {@code StreamSink.onComplete}. The LLD passed only {@link Usage};
 * the finish reason is needed for the OpenAI final chunk and for output checks (t07), and the model
 * is the upstream model that actually answered (DECISIONS.md D-14).
 *
 * @param finishReason why generation stopped
 * @param usage        provider-reported usage of this call ({@link Usage#ZERO} for a cache hit)
 * @param model        model that produced the output
 */
public record Completion(FinishReason finishReason, Usage usage, String model) {
    public Completion {
        Objects.requireNonNull(finishReason, "finishReason");
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(model, "model");
    }
}
