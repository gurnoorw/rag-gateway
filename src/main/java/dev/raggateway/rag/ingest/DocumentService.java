package dev.raggateway.rag.ingest;

import dev.raggateway.rag.ingest.UpsertResult.Outcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * The write side of ingestion (C4, LLD "Indexing worker and the outbox"): the document row and its outbox job are
 * written in one transaction, so a saved document always has a job and a job never points at a document that was
 * not saved. Indexing happens later in the worker; nothing slow runs inside this transaction (a locked read and at
 * most two writes, one connection, D-47).
 *
 * <p>t11 (D-51): the PUT locks the existing row ({@code SELECT … FOR UPDATE}) and compares it with the new version:
 * <ul>
 *   <li><b>unchanged</b>: same hash (title, content, sorted groups) and status {@code queued} or {@code indexed}.
 *       Nothing is written: no job, no embedding.</li>
 *   <li><b>acl_updated</b>: same title and content, other groups, status {@code indexed}. The document's groups and
 *       hash and every chunk's groups are updated in this transaction, so the change is visible at once (also the
 *       safe direction when access is removed). No job. Only for {@code indexed}: if a content job were still
 *       pending, moving the hash to the new groups would make that job stale and its newer content would never be
 *       indexed, so that case queues a full job.</li>
 *   <li><b>queued</b>: everything else, including a {@code failed} document re-PUT unchanged (that is how a dead job
 *       is retried).</li>
 * </ul>
 * The lock serialises the PUT with a concurrent PUT of the same address (no decision from a stale read) and with the
 * worker's chunk swap, which locks the same row first (D-48).
 */
@Service
public class DocumentService {

    private final DocumentStore documents;
    private final OutboxStore outbox;

    public DocumentService(DocumentStore documents, OutboxStore outbox) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    /** Saves the document and queues its indexing job (both or neither), or takes one of the two fast paths. */
    @Transactional
    public UpsertResult upsert(DocumentUpsert document) {
        String hash = document.contentHash();
        Optional<IndexedDocument> locked =
                documents.lockForUpdate(document.tenantId(), document.collection(), document.docId());
        if (locked.isPresent()) {
            IndexedDocument current = locked.get();
            boolean live = current.status() == DocumentStatus.QUEUED || current.status() == DocumentStatus.INDEXED;
            if (live && current.contentHash().equals(hash)) {
                return new UpsertResult(current.id(), Outcome.UNCHANGED);
            }
            if (current.status() == DocumentStatus.INDEXED
                    && current.title().equals(document.title())
                    && current.content().equals(document.content())
                    && !current.aclGroups().equals(document.aclGroups())) {
                documents.updateAcl(current.id(), document.aclGroups(), hash);
                return new UpsertResult(current.id(), Outcome.ACL_UPDATED);
            }
        }
        var id = documents.upsert(document);
        outbox.insert(id, hash);
        return new UpsertResult(id, Outcome.QUEUED);
    }

    /**
     * Deletes the document at this address of this tenant, with its chunks and jobs (one statement, cascade). Another
     * tenant's document is simply not there (C17).
     *
     * @return true if a document was deleted
     */
    @Transactional
    public boolean delete(String tenantId, String collection, String docId) {
        return documents.delete(tenantId, collection, docId);
    }

    /** The document at this address of this tenant; another tenant's document is simply not found (C17). */
    public Optional<IndexedDocument> find(String tenantId, String collection, String docId) {
        return documents.find(tenantId, collection, docId);
    }
}
