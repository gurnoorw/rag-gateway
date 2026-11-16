package dev.raggateway.rag.ingest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * The write side of ingestion (C4, LLD "Indexing worker and the outbox"): the document row and its outbox job are
 * written in one transaction, so a saved document always has a job and a job never points at a document that was
 * not saved. Indexing happens later in the worker; nothing slow runs inside this transaction (two inserts, one
 * connection, D-47).
 *
 * <p>t09 always queues a job. t11 adds the unchanged-hash skip (no job, still 202).
 */
@Service
public class DocumentService {

    private final DocumentStore documents;
    private final OutboxStore outbox;

    public DocumentService(DocumentStore documents, OutboxStore outbox) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    /** Saves the document and queues its indexing job, both or neither. */
    @Transactional
    public UpsertResult upsert(DocumentUpsert document) {
        var id = documents.upsert(document);
        outbox.insert(id, document.contentHash());
        return new UpsertResult(id, true);
    }

    /** The document at this address of this tenant; another tenant's document is simply not found (C17). */
    public Optional<IndexedDocument> find(String tenantId, String collection, String docId) {
        return documents.find(tenantId, collection, docId);
    }
}
