package dev.raggateway.rag.ingest;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Outcome of a {@code PUT}, decided under a row lock on the existing document (t11, D-51).
 *
 * @param documentId surrogate id of the document row
 * @param outcome    what the PUT did
 */
public record UpsertResult(UUID documentId, Outcome outcome) {

    public UpsertResult {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(outcome, "outcome");
    }

    /** The three PUT outcomes; {@link #wireValue()} is the 202 body's {@code status} and the metric's result. */
    public enum Outcome {
        /** Same hash and status {@code queued} or {@code indexed}: nothing written, no job, no embedding (AC-t11-1). */
        UNCHANGED,
        /**
         * Same title and content, other groups, status {@code indexed}: the document's and every chunk's groups
         * were updated in the PUT's transaction; no job, no embedding (AC-t11-3).
         */
        ACL_UPDATED,
        /** Anything else, including a {@code failed} document re-PUT unchanged: saved with status queued and a job. */
        QUEUED;

        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** True when an outbox job was written. */
    public boolean queued() {
        return outcome == Outcome.QUEUED;
    }
}
