package dev.raggateway.rag.ingest;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * The {@code outbox} table (C4). Claims use {@code FOR UPDATE SKIP LOCKED} with a lease (LLD claim SQL).
 *
 * <p><b>Fencing (t11, D-51).</b> {@link #markDone}, {@link #release} and {@link #markDead} take the attempt number
 * the claim returned and change the row only {@code WHERE id = ? AND attempts = ?} (and while it is IN_PROGRESS).
 * Two workers hold the same job only after a lease ran out and the job was claimed again, which incremented
 * {@code attempts}; the stale worker's calls then touch nothing and return false.
 *
 * <p><b>Retry time.</b> A released job stays PENDING with {@code lease_until} set to its retry time; the claim
 * takes a PENDING row only when {@code lease_until} is null or past. No extra column (D-51).
 */
public interface OutboxStore {

    /** Inserts a PENDING job. Must run inside the caller's transaction (the document write's). */
    UUID insert(UUID documentId, String contentHash);

    /**
     * Atomically moves up to {@code max} claimable jobs to IN_PROGRESS with {@code lease_until = now() + lease}
     * and {@code attempts + 1}, oldest first. Claimable: PENDING with {@code lease_until} null or past (a released
     * job waits out its backoff), or IN_PROGRESS whose lease ran out (a crashed worker). One short transaction of
     * its own.
     */
    List<OutboxEvent> claimBatch(int max, Duration lease);

    /**
     * DONE, fenced by {@code attempt}.
     *
     * @return false when the job was claimed again after this claimant's lease ran out, or is gone
     */
    boolean markDone(UUID id, int attempt);

    /**
     * Back to PENDING with the error recorded and {@code lease_until = now() + retryAfter} (the claim skips it
     * until then), fenced by {@code attempt}.
     *
     * @return false when this claimant no longer owns the job (nothing changed)
     */
    boolean release(UUID id, int attempt, String error, Duration retryAfter);

    /**
     * DEAD with the error recorded, fenced by {@code attempt}. In the same transaction, the document becomes
     * {@code failed} only if it still holds this job's hash and is {@code queued}: otherwise a newer PUT owns it, or
     * it is already indexed at that version. Locks the document row before the job row, the order the other
     * writers use (PUT, swap, delete with its cascade), so it cannot deadlock with them.
     */
    DeadLetter markDead(UUID id, int attempt, String error);

    /**
     * Dead jobs nobody has acted on yet: DEAD with no newer job for the same document. A re-PUT (which queues a new
     * job) or a delete (which cascades) clears one, so the {@code rag.outbox.dead} gauge returns to 0 once the
     * problem has been handled instead of staying up forever.
     */
    long countUnresolvedDead();

    /**
     * Result of {@link #markDead}.
     *
     * @param dead           the job is now DEAD (false: fenced out, nothing changed)
     * @param documentFailed the document was set to {@code failed}
     */
    record DeadLetter(boolean dead, boolean documentFailed) {
    }
}
