package dev.raggateway.rag.query;

import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.rag.Embedder;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.RetrievalResult;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.Retriever;
import dev.raggateway.rag.query.ChunkSearchRepository.Row;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/**
 * Hybrid retrieval (t10, DECISIONS.md C17–C19, D-49): a keyword leg and a vector leg over {@link
 * ChunkSearchRepository}, merged with {@link Rrf reciprocal rank fusion}.
 * <ul>
 *   <li><b>Concurrency.</b> In {@code HYBRID} the keyword leg starts at once on a virtual thread; on a second
 *       one the question is embedded ({@link Embedder#embedQuery}, the only place the {@code search_query: }
 *       prefix is added) and then the vector leg runs. Wall time = max(keyword, embed + vector). Each leg holds a
 *       pool connection only for its own statement, so one retrieval uses at most two connections, briefly.</li>
 *   <li><b>Modes.</b> {@code KEYWORD} runs only the keyword leg and makes no embedder call; {@code VECTOR} only
 *       embed + vector. Single-leg modes use the same RRF formula over one list.</li>
 *   <li><b>Failures.</b> An embedder failure in {@code HYBRID} degrades to keyword-only, logged, counted as 0
 *       vector candidates and reported as {@link RetrievalResult#modeUsed()} {@code KEYWORD}: a lexical answer
 *       beats a 503 while Ollama restarts. In {@code VECTOR} it is a 503 ({@code all_providers_failed}: the
 *       embedding provider is the only one this mode has). A database failure in either leg is an error in every
 *       mode; the other leg is cancelled.</li>
 *   <li><b>No Java filtering.</b> Tenant, collection and groups are inside the SQL (C16, C17). Empty groups return
 *       an empty result without SQL or an embedder call.</li>
 *   <li><b>Observability.</b> One INFO line per retrieval with the mode, the leg counts and the chosen chunk ids
 *       (never chunk or question text; the request id comes from the log pattern's MDC). Metrics:
 *       {@code rag.retrieval.duration{mode}}, {@code rag.retrieval.leg.duration{leg}} for
 *       {@code keyword}/{@code embed}/{@code vector}, and {@code rag.retrieval.candidates{leg}}.</li>
 * </ul>
 */
@Component
public class HybridRetriever implements Retriever, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    private final ChunkSearchRepository repository;
    private final Embedder embedder;
    private final RagProperties.Retrieval settings;
    private final MeterRegistry meters;
    private final ExecutorService legs = Executors.newVirtualThreadPerTaskExecutor();

    public HybridRetriever(ChunkSearchRepository repository, Embedder embedder, RagProperties rag,
                           MeterRegistry meters) {
        this.repository = repository;
        this.embedder = embedder;
        this.settings = rag.retrieval();
        this.meters = meters;
    }

    @Override
    public RetrievalResult retrieve(RetrievalQuery q) {
        long start = System.nanoTime();
        try {
            Outcome o = q.groups().isEmpty()
                    ? new Outcome(new RetrievalResult(List.of(), q.mode()), 0, 0)
                    : run(q);
            log.info("retrieval mode={} mode_used={} keyword_candidates={} vector_candidates={} chunks={}",
                    label(q.mode()), label(o.result().modeUsed()), o.keywordCount(), o.vectorCount(),
                    o.result().chunks().stream().map(RetrievedChunk::chunkId).toList());
            return o.result();
        } finally {
            Timer.builder(MetricNames.RETRIEVAL_DURATION).tag("mode", label(q.mode())).register(meters)
                    .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /** A result plus the leg sizes for the log line (0 for a leg that did not run). */
    private record Outcome(RetrievalResult result, int keywordCount, int vectorCount) {
    }

    private Outcome run(RetrievalQuery q) {
        int n = settings.candidates();
        Future<List<Row>> keyword = q.mode() == RetrievalMode.VECTOR ? null
                : legs.submit(leg("keyword", () -> repository.keyword(q.tenantId(), q.collection(), q.groups(),
                        q.question(), n)));
        Future<VectorLeg> vector = q.mode() == RetrievalMode.KEYWORD ? null
                : legs.submit(() -> vectorLeg(q, n));

        List<Row> keywordRows = List.of();
        VectorLeg vectorLeg = null;
        try {
            if (keyword != null) keywordRows = await(keyword);
            if (vector != null) vectorLeg = await(vector);
        } catch (RuntimeException | Error e) {
            cancel(keyword);
            cancel(vector);
            throw e;
        }

        RetrievalMode used = q.mode();
        List<Row> vectorRows = List.of();
        if (vectorLeg != null) {
            if (vectorLeg.embedFailure() != null) {
                if (q.mode() == RetrievalMode.VECTOR) {
                    throw new GatewayException(ErrorCode.SERVICE_UNAVAILABLE,
                            "the embedding provider is unavailable; vector retrieval cannot run",
                            vectorLeg.embedFailure());
                }
                log.warn("embedder failed, hybrid retrieval degraded to keyword-only: {}",
                        vectorLeg.embedFailure().toString());
                used = RetrievalMode.KEYWORD;
            } else {
                vectorRows = vectorLeg.rows();
            }
        }
        if (keyword != null) candidates("keyword", keywordRows.size());
        if (vector != null) candidates("vector", vectorRows.size());

        return new Outcome(new RetrievalResult(fuse(keywordRows, vectorRows, q.topK()), used), keywordRows.size(),
                vectorRows.size());
    }

    /** Embeds, then searches. An embedder failure is returned, not thrown, so the caller decides by mode. */
    private VectorLeg vectorLeg(RetrievalQuery q, int n) {
        float[] v;
        try {
            v = timed("embed", () -> embedder.embedQuery(q.question()));
        } catch (RuntimeException e) {
            return new VectorLeg(List.of(), e);
        }
        return new VectorLeg(timed("vector", () -> repository.vector(q.tenantId(), q.collection(), q.groups(), v, n)),
                null);
    }

    private record VectorLeg(List<Row> rows, RuntimeException embedFailure) {
    }

    private List<RetrievedChunk> fuse(List<Row> keywordRows, List<Row> vectorRows, int topK) {
        Map<String, Row> byId = new HashMap<>();
        keywordRows.forEach(r -> byId.putIfAbsent(r.chunkId(), r));
        vectorRows.forEach(r -> byId.putIfAbsent(r.chunkId(), r));
        List<Rrf.Ranked> fused = Rrf.fuse(settings.rrfK(), ids(keywordRows), ids(vectorRows));
        List<RetrievedChunk> out = new ArrayList<>(Math.min(topK, fused.size()));
        for (Rrf.Ranked r : fused.subList(0, Math.min(topK, fused.size()))) {
            Row row = byId.get(r.chunkId());
            out.add(new RetrievedChunk(row.chunkId(), row.docId(), row.title(), row.ordinal(), row.text(), r.score(),
                    r.keywordRank(), r.vectorRank()));
        }
        return out;
    }

    private static List<String> ids(List<Row> rows) {
        return rows.stream().map(Row::chunkId).toList();
    }

    private <T> Callable<T> leg(String name, Supplier<T> work) {
        return () -> timed(name, work);
    }

    private <T> T timed(String leg, Supplier<T> work) {
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            Timer.builder(MetricNames.RETRIEVAL_LEG_DURATION).tag("leg", leg).register(meters)
                    .record(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    private void candidates(String leg, int count) {
        DistributionSummary.builder(MetricNames.RETRIEVAL_CANDIDATES).tag("leg", leg).register(meters).record(count);
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new IllegalStateException("retrieval leg failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a retrieval leg", e);
        }
    }

    private static void cancel(Future<?> future) {
        if (future != null) future.cancel(true);
    }

    private static String label(RetrievalMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() {
        legs.shutdownNow();
    }
}
