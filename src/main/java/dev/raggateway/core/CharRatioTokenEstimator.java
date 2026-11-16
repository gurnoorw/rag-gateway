package dev.raggateway.core;

/**
 * Character-ratio {@link TokenEstimator} (DECISIONS.md D-11): {@code ceil(chars / charsPerToken)} per piece
 * of text, plus a fixed overhead per message for role and framing tokens. With the default 3.0 characters
 * per token it over-estimates typical English text (closer to 4), which is the safe direction for a
 * reservation. Immutable, so thread-safe.
 */
public final class CharRatioTokenEstimator implements TokenEstimator {

    /** Default characters per token. */
    public static final double DEFAULT_CHARS_PER_TOKEN = 3.0;
    /** Tokens added per message for role and message framing. */
    public static final int PER_MESSAGE_OVERHEAD = 4;

    private final double charsPerToken;

    public CharRatioTokenEstimator() {
        this(DEFAULT_CHARS_PER_TOKEN);
    }

    public CharRatioTokenEstimator(double charsPerToken) {
        if (!(charsPerToken > 0) || Double.isInfinite(charsPerToken)) {
            throw new IllegalArgumentException("charsPerToken must be a positive number");
        }
        this.charsPerToken = charsPerToken;
    }

    @Override
    public int estimateInput(ChatRequest request) {
        long total = 0;
        for (Message m : request.messages()) {
            total += estimate(m.content()) + PER_MESSAGE_OVERHEAD;
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        // Code points, not UTF-16 units: an emoji counts as one character here.
        int chars = text.codePointCount(0, text.length());
        return (int) Math.ceil(chars / charsPerToken);
    }
}
