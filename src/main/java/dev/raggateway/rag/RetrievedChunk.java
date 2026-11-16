package dev.raggateway.rag;

/** A chunk returned by retrieval, in final rank order. */
public record RetrievedChunk(String chunkId, String docId, String title, int ordinal, String text) {
}
