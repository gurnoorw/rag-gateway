package dev.raggateway.rag.ingest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** The {@code outbox} table (C4). Claims use {@code FOR UPDATE SKIP LOCKED} with a lease (LLD claim SQL). */
public interface OutboxStore {

    /** Inserts a PENDING job. Must run inside the caller's transaction (the document write's). */
    UUID insert(UUID documentId, String contentHash);

    /**
     * Atomically moves up to {@code max} PENDING jobs, and IN_PROGRESS jobs whose lease ran out, to
     * IN_PROGRESS with {@code lease_until = now() + lease} and {@code attempts + 1}; oldest first. One short
     * transaction of its own.
     */
    List<OutboxEvent> claimBatch(int max, Duration lease);

    void markDone(UUID id);

    /** Back to PENDING with the error recorded (retried on a later poll). */
    void release(UUID id, String error);

    /** Terminal failure (t11: after the attempt limit). */
    void markDead(UUID id, String error);
}
