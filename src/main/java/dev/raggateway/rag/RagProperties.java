package dev.raggateway.rag;

import dev.raggateway.rag.ingest.RetryPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Objects;

/**
 * RAG settings ({@code gateway.rag}): ingestion (t09, DECISIONS.md D-47) and retrieval and answering (t10, D-49). Defaults live here; application.yml only
 * overrides.
 *
 * @param embedder            which {@link Embedder} the app uses: {@code ollama} (default) or {@code fake}
 *                            (the default under the test profile, D-18)
 * @param embedModel          Ollama embedding model (default {@code nomic-embed-text}, CLAUDE.md)
 * @param embedDimensions     vector length; must match the {@code vector(768)} column (C1)
 * @param embedBatchSize      most chunks per embed call (default 20): bounds one request and one response
 * @param embedTimeout        per embed call (default 60 s: a batch of 20 on a laptop CPU)
 * @param maxDocumentBytes    largest {@code PUT} body, checked before parsing (default 1 MB, 413 above)
 * @param maxConcurrentWrites {@code PUT}s past the write filter at once per instance (default 4; the next gets
 *                            503 {@code overloaded}): bounds the heap that document bodies can take
 * @param chunk               chunk sizes in estimated tokens
 * @param worker              the indexing worker
 * @param retrieval           the two search legs and their fusion (t10, D-49)
 * @param answer              the RAG answer request (t10, D-49)
 */
@ConfigurationProperties("gateway.rag")
public record RagProperties(
        @DefaultValue("ollama") EmbedderKind embedder,
        @DefaultValue("nomic-embed-text") String embedModel,
        @DefaultValue("768") int embedDimensions,
        @DefaultValue("20") int embedBatchSize,
        @DefaultValue("60s") Duration embedTimeout,
        @DefaultValue("1MB") DataSize maxDocumentBytes,
        @DefaultValue("4") int maxConcurrentWrites,
        @DefaultValue Chunking chunk,
        @DefaultValue Worker worker,
        @DefaultValue Retrieval retrieval,
        @DefaultValue Answer answer) {

    public enum EmbedderKind { OLLAMA, FAKE }

    public RagProperties {
        embedder = embedder == null ? EmbedderKind.OLLAMA : embedder;
        embedModel = embedModel == null || embedModel.isBlank() ? "nomic-embed-text" : embedModel;
        maxDocumentBytes = maxDocumentBytes == null ? DataSize.ofMegabytes(1) : maxDocumentBytes;
        embedTimeout = positive(embedTimeout, Duration.ofSeconds(60), "embed-timeout");
        chunk = chunk == null ? Chunking.defaults() : chunk;
        worker = worker == null ? Worker.defaults() : worker;
        retrieval = retrieval == null ? Retrieval.defaults() : retrieval;
        answer = answer == null ? Answer.defaults() : answer;
        require(embedDimensions > 0, "embed-dimensions must be > 0");
        require(embedBatchSize > 0, "embed-batch-size must be > 0");
        require(maxDocumentBytes.toBytes() > 0, "max-document-bytes must be > 0");
        require(maxConcurrentWrites > 0, "max-concurrent-writes must be > 0");
    }

    public static RagProperties defaults() {
        return new RagProperties(null, null, 768, 20, null, null, 4, null, null, null, null);
    }

    /**
     * @param targetTokens  a chunk is closed before it would grow past this (default 400)
     * @param overlapTokens whole sentences from the end of the previous chunk in the same section, up to this
     *                      many tokens (default 50)
     * @param maxTokens     hard limit; only a single sentence or code line longer than this is cut (default 400)
     */
    public record Chunking(@DefaultValue("400") int targetTokens,
                           @DefaultValue("50") int overlapTokens,
                           @DefaultValue("400") int maxTokens) {
        public Chunking {
            require(targetTokens > 0, "chunk.target-tokens must be > 0");
            require(overlapTokens >= 0 && overlapTokens < targetTokens,
                    "chunk.overlap-tokens must be >= 0 and < chunk.target-tokens");
            require(maxTokens >= targetTokens, "chunk.max-tokens must be >= chunk.target-tokens");
        }

        public static Chunking defaults() {
            return new Chunking(400, 50, 400);
        }
    }

    /**
     * @param enabled   runs the scheduled poller (off under the test profile: tests call
     *                  {@code IndexingWorker.pollOnce()}, and only {@code IngestionE2EIT} turns it on)
     * @param pollDelay delay between the end of one poll and the start of the next (default 1 s; polls never
     *                  overlap)
     * @param claimBatch most jobs claimed per poll (default 4), handled one at a time
     * @param lease     how long a claim is held before another worker may take it (default 5 min; covers the
     *                  whole batch even on a slow laptop)
     * @param maxAttempts claims before a job is dead (default 5; t11, D-51)
     * @param retryBase   wait after the first failed attempt, doubled after each further one (default 30 s)
     * @param retryMax    longest wait between attempts (default 10 min)
     */
    public record Worker(@DefaultValue("true") boolean enabled,
                         @DefaultValue("1s") Duration pollDelay,
                         @DefaultValue("4") int claimBatch,
                         @DefaultValue("5m") Duration lease,
                         @DefaultValue("5") int maxAttempts,
                         @DefaultValue("30s") Duration retryBase,
                         @DefaultValue("10m") Duration retryMax) {
        public Worker {
            pollDelay = positive(pollDelay, Duration.ofSeconds(1), "worker.poll-delay");
            lease = positive(lease, Duration.ofMinutes(5), "worker.lease");
            require(claimBatch > 0, "worker.claim-batch must be > 0");
            retryBase = retryBase == null ? Duration.ofSeconds(30) : retryBase;
            retryMax = retryMax == null ? Duration.ofMinutes(10) : retryMax;
            new RetryPolicy(maxAttempts, retryBase, retryMax);   // validates the three together
        }

        public static Worker defaults() {
            return new Worker(true, null, 4, null, 5, null, null);
        }

        /** The worker's retry policy (D-51). */
        public RetryPolicy retryPolicy() {
            return new RetryPolicy(maxAttempts, retryBase, retryMax);
        }
    }

    /** pgvector HNSW iterative index scan mode ({@code hnsw.iterative_scan}, pgvector 0.8). */
    public enum IterativeScan {
        OFF, STRICT_ORDER, RELAXED_ORDER;

        /** The value for {@code SET LOCAL hnsw.iterative_scan}. */
        public String sqlValue() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * @param candidates    rows each leg returns before fusion (default 50)
     * @param rrfK          reciprocal rank fusion constant: score = sum of 1 / (k + rank), rank 1-based (default 60)
     * @param efSearch      {@code SET LOCAL hnsw.ef_search} for the vector leg (default 100). pgvector's default
     *                      is 40, which caps an HNSW scan at 40 rows: fewer than the 50 candidates even with no
     *                      filter. Must be at least {@code candidates}.
     * @param iterativeScan {@code SET LOCAL hnsw.iterative_scan} (default {@code strict_order}): keeps scanning the
     *                      index until the tenant, collection and ACL filter has let {@code candidates} rows
     *                      through, in exact distance order (D-49; t12 proves the under-fill without it)
     * @param maxScanTuples {@code SET LOCAL hnsw.max_scan_tuples} (default 20,000, pgvector's default): bounds the
     *                      work of an iterative scan when the filter matches almost nothing
     */
    public record Retrieval(@DefaultValue("50") int candidates,
                            @DefaultValue("60") int rrfK,
                            @DefaultValue("100") int efSearch,
                            @DefaultValue("strict_order") IterativeScan iterativeScan,
                            @DefaultValue("20000") int maxScanTuples) {
        public Retrieval {
            iterativeScan = iterativeScan == null ? IterativeScan.STRICT_ORDER : iterativeScan;
            require(candidates > 0 && candidates <= 200, "retrieval.candidates must be 1..200");
            require(rrfK > 0, "retrieval.rrf-k must be > 0");
            require(efSearch >= candidates && efSearch <= 1000, "retrieval.ef-search must be candidates..1000");
            require(maxScanTuples > 0, "retrieval.max-scan-tuples must be > 0");
        }

        public static Retrieval defaults() {
            return new Retrieval(50, 60, 100, IterativeScan.STRICT_ORDER, 20_000);
        }
    }

    /**
     * @param maxTokens        {@code max_tokens} of the answer request (default 512)
     * @param maxQuestionChars longest question accepted (default 2,000 characters; 400 above)
     * @param defaultTopK      chunks put in the prompt when the request names none (default 5; the request may
     *                         ask for 1..20, {@link RetrievalQuery})
     */
    public record Answer(@DefaultValue("512") int maxTokens,
                         @DefaultValue("2000") int maxQuestionChars,
                         @DefaultValue("5") int defaultTopK) {
        public Answer {
            require(maxTokens > 0, "answer.max-tokens must be > 0");
            require(maxQuestionChars > 0, "answer.max-question-chars must be > 0");
            require(defaultTopK >= 1 && defaultTopK <= 20, "answer.default-top-k must be 1..20");
        }

        public static Answer defaults() {
            return new Answer(512, 2000, 5);
        }
    }

    private static Duration positive(Duration value, Duration fallback, String name) {
        Duration d = value == null ? fallback : value;
        Objects.requireNonNull(d);
        require(!d.isNegative() && !d.isZero(), name + " must be > 0");
        return d;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException("gateway.rag." + message);
    }
}
