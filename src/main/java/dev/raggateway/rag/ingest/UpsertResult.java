package dev.raggateway.rag.ingest;

import java.util.UUID;

/**
 * Outcome of a {@code PUT}. t09 always queues; t11 adds {@code unchanged} (same hash: no job, still 202, AC-t11-1).
 *
 * @param documentId surrogate id of the document row
 * @param queued     true when an outbox job was written
 */
public record UpsertResult(UUID documentId, boolean queued) {
}
