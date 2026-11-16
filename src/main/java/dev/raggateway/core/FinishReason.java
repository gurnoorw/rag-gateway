package dev.raggateway.core;

/**
 * Why generation stopped, in OpenAI terms. Claude {@code end_turn}/{@code stop_sequence} map to
 * {@link #STOP}, {@code max_tokens} to {@link #LENGTH}, {@code refusal} to {@link #CONTENT_FILTER}.
 */
public enum FinishReason {
    STOP("stop"), LENGTH("length"), CONTENT_FILTER("content_filter");

    private final String wire;

    FinishReason(String wire) {
        this.wire = wire;
    }

    /** Value of {@code finish_reason} on the OpenAI wire format. */
    public String wire() {
        return wire;
    }
}
