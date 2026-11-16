package dev.raggateway.rag.ingest;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * A {@code documents} row. The worker reads it by {@link #id()}; {@code GET} reads it by address and returns
 * everything but the content.
 */
public record IndexedDocument(UUID id, String tenantId, String collection, String docId, String title,
                              String content, Set<String> aclGroups, String contentHash, DocumentStatus status,
                              int chunkCount, Instant updatedAt) {
    public IndexedDocument {
        aclGroups = Set.copyOf(aclGroups);
    }
}
