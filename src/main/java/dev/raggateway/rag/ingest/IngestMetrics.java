package dev.raggateway.rag.ingest;

import dev.raggateway.metrics.MetricNames;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ingestion metrics (t11, D-51):
 * <ul>
 *   <li>{@code rag.ingest.documents{result}}: one count per PUT outcome (queued, unchanged, acl_updated), per worker
 *       outcome (indexed, stale_skipped, retried, dead) and per delete that removed a document (deleted). A series
 *       is registered at its first event, not at 0 on startup: {@code RagQueryIT} (rag/query, frozen this round)
 *       asserts that every {@code rag_*} series with a {@code result} label in its context carries a RAG-query
 *       value, and eager zero series would break it.</li>
 *   <li>{@code rag.outbox.dead}: unresolved dead jobs ({@link OutboxStore#countUnresolvedDead}). The worker sets it
 *       after each poll from one count query; the gauge only reads a number in memory, so a scrape never touches
 *       the database. A non-zero value is the alert.</li>
 * </ul>
 */
@Component
public class IngestMetrics {

    /** The {@code result} values; bounded (D-38). */
    public enum Result {
        QUEUED, UNCHANGED, ACL_UPDATED, INDEXED, STALE_SKIPPED, RETRIED, DEAD, DELETED;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final MeterRegistry registry;
    private final Map<Result, Counter> counters = new ConcurrentHashMap<>();
    private final AtomicLong deadJobs = new AtomicLong();

    public IngestMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Gauge.builder(MetricNames.OUTBOX_DEAD, deadJobs, AtomicLong::get)
                .description("Dead indexing jobs with no newer job for their document (set by the worker after each poll)")
                .strongReference(true)
                .register(registry);
    }

    /** Metrics into a private registry nobody scrapes: for a worker built outside Spring (tests, tools). */
    public static IngestMetrics detached() {
        return new IngestMetrics(new SimpleMeterRegistry());
    }

    public void count(Result result) {
        counters.computeIfAbsent(result, r -> Counter.builder(MetricNames.INGEST_DOCUMENTS)
                .description("Document PUT outcomes, indexing job outcomes and deletes")
                .tag("result", r.label())
                .register(registry)).increment();
    }

    public void count(UpsertResult.Outcome outcome) {
        count(switch (outcome) {
            case QUEUED -> Result.QUEUED;
            case UNCHANGED -> Result.UNCHANGED;
            case ACL_UPDATED -> Result.ACL_UPDATED;
        });
    }

    /** Sets the {@code rag.outbox.dead} gauge. */
    public void deadJobs(long count) {
        deadJobs.set(count);
    }

    /** The current gauge value (for tests and logs). */
    public long deadJobs() {
        return deadJobs.get();
    }
}
