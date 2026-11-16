package dev.raggateway.rag.ingest;

import java.util.UUID;

/**
 * A claimed {@code outbox} row.
 *
 * @param attempts claims so far, including this one (the claim increments it)
 */
public record OutboxEvent(UUID id, UUID documentId, String contentHash, int attempts) {
}
