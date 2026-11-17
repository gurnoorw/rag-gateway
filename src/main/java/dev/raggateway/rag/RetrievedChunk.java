package dev.raggateway.rag;

/**
 * A chunk returned by retrieval, in final rank order. Carries how it got there, so the RAG response can list its
 * sources and the t13 evaluation can score keyword, vector and hybrid modes from the same records (D-49).
 *
 * @param chunkId     {@code chunks.chunk_id}
 * @param docId       the document's id in its collection (what citations and the eval's gold labels name)
 * @param title       document title
 * @param ordinal     position of the chunk in its document
 * @param text        chunk text, as put in the prompt
 * @param score       reciprocal rank fusion score in hybrid mode; in a single-leg mode, the same formula over that
 *                    one list, so scores are comparable within a response, not across modes
 * @param keywordRank 1-based rank in the keyword list, 0 when the chunk was not in it (or the leg did not run)
 * @param vectorRank  1-based rank in the vector list, 0 when the chunk was not in it (or the leg did not run)
 */
public record RetrievedChunk(String chunkId, String docId, String title, int ordinal, String text, double score,
                             int keywordRank, int vectorRank) {
    public RetrievedChunk {
        if (keywordRank < 0 || vectorRank < 0) throw new IllegalArgumentException("ranks are 1-based, 0 = absent");
    }
}
