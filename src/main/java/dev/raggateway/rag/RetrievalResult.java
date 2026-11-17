package dev.raggateway.rag;

import java.util.List;

/**
 * What a retrieval returned and how (D-49).
 *
 * @param chunks   final order, at most {@code topK}
 * @param modeUsed the mode that actually ran: {@link RetrievalMode#KEYWORD} when a {@code HYBRID} retrieval lost
 *                 its vector leg (the embedder failed), otherwise the requested mode
 */
public record RetrievalResult(List<RetrievedChunk> chunks, RetrievalMode modeUsed) {
    public RetrievalResult {
        chunks = List.copyOf(chunks);
        java.util.Objects.requireNonNull(modeUsed, "modeUsed");
    }

    public boolean degraded(RetrievalQuery query) {
        return modeUsed != query.mode();
    }
}
