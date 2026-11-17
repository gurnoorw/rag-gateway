package dev.raggateway.rag.ingest;

import java.util.UUID;

/**
 * A claimed {@code outbox} row.
 *
 * @param attempts claims so far, including this one (the claim increments it). It is also the fencing token: the
 *                 claimant passes it back to {@link OutboxStore#markDone}, {@link OutboxStore#release} and
 *                 {@link OutboxStore#markDead}, which change the row only while it still has this value, so a worker
 *                 whose lease ran out and whose job was claimed again changes nothing (t11, D-51)
 */
public record OutboxEvent(UUID id, UUID documentId, String contentHash, int attempts) {
}
