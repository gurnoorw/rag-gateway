package dev.raggateway.rag.ingest;

import dev.raggateway.rag.ChunkText;

import java.util.List;
import java.util.UUID;

/** The {@code chunks} table. */
public interface ChunkStore {

    /**
     * Deletes the document's chunks and inserts the new ones in one transaction, copying tenant, collection,
     * doc id, title and ACL groups from {@code document} (C16, C17). Readers see the old set or the new one,
     * never a mix; running it twice gives the same rows. In the same transaction it sets the document's status
     * to {@code indexed} and its chunk count, so the count never disagrees with the rows. Inserts go in JDBC
     * batches of at most 100 rows (bounded memory, D-47).
     *
     * @param vectors one per chunk, same order, each {@link dev.raggateway.rag.Embedder#dimensions()} long
     */
    void replaceForDocument(IndexedDocument document, List<ChunkText> chunks, List<float[]> vectors);

    int countForDocument(UUID documentId);
}
