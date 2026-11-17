package dev.raggateway.rag.ingest;

import java.util.Optional;
import java.util.Set;
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

    /**
     * The document at this address, its row locked ({@code SELECT … FOR UPDATE}) until the caller's transaction
     * ends (t11, D-51). The PUT decides unchanged / acl_updated / queued under this lock, so two concurrent PUTs
     * cannot both decide from a stale read, and a concurrent chunk swap (which locks the same row, D-48) is
     * serialised with it. Must run inside a transaction. Empty when there is no row yet (nothing to lock; the
     * upsert's unique key decides between concurrent first PUTs).
     */
    Optional<IndexedDocument> lockForUpdate(String tenantId, String collection, String docId);

    /**
     * The ACL fast path (t11, D-51): sets the document's groups and hash and {@code updated_at}, and every chunk's
     * {@code acl_groups}, in the caller's transaction. Status and chunk count are unchanged; nothing is embedded.
     * The caller holds the document row lock ({@link #lockForUpdate}), taken before the chunk rows, the same order
     * as the swap.
     *
     * @return the number of chunk rows updated
     */
    int updateAcl(UUID id, Set<String> aclGroups, String contentHash);

    /**
     * Deletes the document at this address in one statement; its chunks and outbox jobs go with it
     * ({@code ON DELETE CASCADE}), so a reader sees the chunks or none (t11, D-51).
     *
     * @return true if a row was deleted
     */
    boolean delete(String tenantId, String collection, String docId);

    Optional<IndexedDocument> find(String tenantId, String collection, String docId);

    Optional<IndexedDocument> findById(UUID id);
}
