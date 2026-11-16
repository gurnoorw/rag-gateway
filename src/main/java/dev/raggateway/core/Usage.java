package dev.raggateway.core;

/** Token usage of one provider call, as reported by the provider (or estimated, see {@link Attempt}). */
public record Usage(int inputTokens, int outputTokens) {

    public static final Usage ZERO = new Usage(0, 0);

    public Usage {
        if (inputTokens < 0 || outputTokens < 0) throw new IllegalArgumentException("negative usage");
    }

    public int total() {
        return inputTokens + outputTokens;
    }

    public Usage plus(Usage other) {
        return new Usage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
