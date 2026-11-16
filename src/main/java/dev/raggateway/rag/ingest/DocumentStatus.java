package dev.raggateway.rag.ingest;

import java.util.Locale;

/** {@code documents.status}. {@code FAILED} is set by t11 when a job goes dead; t09 sets only the first two. */
public enum DocumentStatus {
    QUEUED, INDEXED, FAILED;

    /** The value stored in the column and returned by {@code GET}. */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DocumentStatus fromDb(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
