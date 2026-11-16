package dev.raggateway.rag.ingest;

import java.util.Optional;
import java.util.UUID;

/** The {@code documents} table. Every lookup by address includes the tenant (C17). */
public interface DocumentStore {

    /**
     * Inserts or replaces the document at {@code (tenant, collection, docId)}: sets title, content, groups and
     * hash, status {@code queued}, {@code updated_at = now()}; keeps the surrogate id and the chunk count of
     * an existing row. Runs inside the caller's transaction.
     *
     * @return the surrogate id
     */
    UUID upsert(DocumentUpsert document);

    Optional<IndexedDocument> find(String tenantId, String collection, String docId);

    Optional<IndexedDocument> findById(UUID id);

    /** Status {@code failed} (t11, when a job goes dead). Indexed status is set by {@link ChunkStore}. */
    void markFailed(UUID id);
}
