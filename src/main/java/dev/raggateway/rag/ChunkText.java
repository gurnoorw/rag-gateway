package dev.raggateway.rag;

/**
 * @param ordinal         0-based position in the document
 * @param text            chunk text, never cut mid-sentence (t09)
 * @param estimatedTokens size by the shared {@link dev.raggateway.core.TokenEstimator}
 */
public record ChunkText(int ordinal, String text, int estimatedTokens) {
}
