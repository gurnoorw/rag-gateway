package dev.raggateway.rag;

import java.util.List;

/**
 * Text to vectors. Documents and queries are separate methods because {@code nomic-embed-text} expects
 * different task prefixes ({@code search_document: } / {@code search_query: }, G11), and each implementation
 * applies its own. Implementations: Ollama (t09) and a deterministic fake for tests and this build
 * environment. Vectors have {@link #dimensions()} entries (768, CLAUDE.md override).
 */
public interface Embedder {

    int dimensions();

    /** One call for the whole batch (AC-t09-7). Result order matches input order. */
    List<float[]> embedDocuments(List<String> texts);

    float[] embedQuery(String text);
}
