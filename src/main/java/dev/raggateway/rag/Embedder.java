package dev.raggateway.rag;

import java.util.List;

/**
 * Text to vectors. Documents and queries are separate methods because {@code nomic-embed-text} expects
 * different task prefixes ({@code search_document: } / {@code search_query: }, G11), and each implementation
 * applies its own. Implementations: Ollama (t09) and a deterministic fake for tests and this build
 * environment. Vectors have {@link #dimensions()} entries (768, CLAUDE.md override).
 */
public interface Embedder {

    /**
     * {@code nomic-embed-text} task prefixes (D-47). Defined once here; only the model adapter adds them
     * ({@code OllamaEmbedder}: documents in {@code embedDocuments}, questions in {@code embedQuery}); callers pass
     * plain text.
     */
    String DOCUMENT_PREFIX = "search_document: ";
    String QUERY_PREFIX = "search_query: ";

    int dimensions();

    /** One call for the whole batch (AC-t09-7). Result order matches input order. */
    List<float[]> embedDocuments(List<String> texts);

    float[] embedQuery(String text);
}
