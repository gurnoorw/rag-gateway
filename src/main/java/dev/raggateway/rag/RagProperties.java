package dev.raggateway.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Objects;

/**
 * RAG ingestion settings ({@code gateway.rag}, t09, DECISIONS.md D-47). Defaults live here; application.yml only
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
        @DefaultValue Worker worker) {

    public enum EmbedderKind { OLLAMA, FAKE }

    public RagProperties {
        embedder = embedder == null ? EmbedderKind.OLLAMA : embedder;
        embedModel = embedModel == null || embedModel.isBlank() ? "nomic-embed-text" : embedModel;
        maxDocumentBytes = maxDocumentBytes == null ? DataSize.ofMegabytes(1) : maxDocumentBytes;
        embedTimeout = positive(embedTimeout, Duration.ofSeconds(60), "embed-timeout");
        chunk = chunk == null ? Chunking.defaults() : chunk;
        worker = worker == null ? Worker.defaults() : worker;
        require(embedDimensions > 0, "embed-dimensions must be > 0");
        require(embedBatchSize > 0, "embed-batch-size must be > 0");
        require(maxDocumentBytes.toBytes() > 0, "max-document-bytes must be > 0");
        require(maxConcurrentWrites > 0, "max-concurrent-writes must be > 0");
    }

    public static RagProperties defaults() {
        return new RagProperties(null, null, 768, 20, null, null, 4, null, null);
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
     */
    public record Worker(@DefaultValue("true") boolean enabled,
                         @DefaultValue("1s") Duration pollDelay,
                         @DefaultValue("4") int claimBatch,
                         @DefaultValue("5m") Duration lease) {
        public Worker {
            pollDelay = positive(pollDelay, Duration.ofSeconds(1), "worker.poll-delay");
            lease = positive(lease, Duration.ofMinutes(5), "worker.lease");
            require(claimBatch > 0, "worker.claim-batch must be > 0");
        }

        public static Worker defaults() {
            return new Worker(true, null, 4, null);
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
