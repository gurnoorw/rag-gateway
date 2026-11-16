package dev.raggateway.rag;

import java.util.List;

/**
 * Splits a document on headings, then paragraphs, then sentences; target size and overlap in tokens come
 * from config (default ~400 / ~50, t09). Deterministic: the same input gives the same chunks.
 */
public interface Chunker {

    List<ChunkText> split(String title, String content);
}
