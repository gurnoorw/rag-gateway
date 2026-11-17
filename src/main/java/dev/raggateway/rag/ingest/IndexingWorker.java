package dev.raggateway.rag.ingest;

import dev.raggateway.rag.ChunkText;
import dev.raggateway.rag.Chunker;
import dev.raggateway.rag.Embedder;
import dev.raggateway.rag.ingest.IngestMetrics.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns outbox jobs into chunk rows (t09, t11; LLD "Indexing worker and the outbox", DECISIONS.md C4, D-47, D-48,
 * D-51).
 *
 * <p>{@link #pollOnce()} claims at most {@code claimBatch} jobs (default 4) with one lease (default 5 min) and
 * handles them one at a time:
 * <ol>
 *   <li>read the document ({@link DocumentStore#findById}: one short connection). A document that is gone, or that
 *       no longer holds the job's hash (a newer PUT queued its own job, or the ACL fast path moved the hash), ends
 *       the job as done without embedding: {@code stale_skipped};</li>
 *   <li>chunk it;</li>
 *   <li>embed {@code <title>\n\n<chunk text>} in batches of at most {@code embedBatchSize} chunks (default 20), one
 *       embedder call per batch. No transaction is open and no connection is held here: this class is not
 *       transactional, and every store call returns its connection before the next step (AC-t09-15);</li>
 *   <li>{@link ChunkStore#replaceForDocument}: one transaction that swaps the chunks and sets status and count,
 *       only if the document still holds the version read in step 1 (D-48); otherwise {@code stale_skipped};</li>
 *   <li>{@link OutboxStore#markDone}, fenced by the claim's attempt number.</li>
 * </ol>
 *
 * <p><b>Failures (t11, D-51).</b> A failed attempt below {@code max-attempts} (5) is released with backoff
 * {@code retry-base × 2^(attempts−1)}, at most {@code retry-max} (30 s, 1, 2, 4 min), kept in {@code lease_until}:
 * {@code retried}. The attempt that reaches the limit makes the job DEAD, and the document {@code failed} if it
 * still holds that job's hash: {@code dead}. A job claimed with more attempts than the limit (its earlier claims
 * crashed or hung past the lease, e.g. a document that kills the worker) is made DEAD without being processed
 * again, so a poison document cannot take the worker down forever. Every terminal call is fenced, so a worker
 * whose lease ran out changes nothing. After each poll the {@code rag.outbox.dead} gauge is refreshed with one
 * count query.
 *
 * <p>In the app one scheduled poller per instance calls {@link #scheduledPoll()} with a fixed delay, so polls
 * never overlap; it keeps polling without the delay while full batches succeed (D-48) ({@code RagConfig}, off
 * under the test profile). Tests call {@link #pollOnce()} directly.
 */
public final class IndexingWorker {

    private static final Logger log = LoggerFactory.getLogger(IndexingWorker.class);
    private static final int MAX_ERROR_CHARS = 1000;

    private final OutboxStore outbox;
    private final DocumentStore documents;
    private final ChunkStore chunks;
    private final Chunker chunker;
    private final Embedder embedder;
    private final int claimBatch;
    private final Duration lease;
    private final int embedBatchSize;
    private volatile WorkerRetryProperties retry;
    private volatile IngestMetrics metrics;

    /** The t09 constructor ({@code RagConfig}): default retry settings and detached metrics until {@link #use}. */
    public IndexingWorker(OutboxStore outbox, DocumentStore documents, ChunkStore chunks, Chunker chunker,
                          Embedder embedder, int claimBatch, Duration lease, int embedBatchSize) {
        this(outbox, documents, chunks, chunker, embedder, claimBatch, lease, embedBatchSize,
                WorkerRetryProperties.defaults(), IngestMetrics.detached());
    }

    public IndexingWorker(OutboxStore outbox, DocumentStore documents, ChunkStore chunks, Chunker chunker,
                          Embedder embedder, int claimBatch, Duration lease, int embedBatchSize,
                          WorkerRetryProperties retry, IngestMetrics metrics) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (claimBatch <= 0 || embedBatchSize <= 0) throw new IllegalArgumentException("batch sizes must be > 0");
        this.claimBatch = claimBatch;
        this.embedBatchSize = embedBatchSize;
        use(retry, metrics);
    }

    /**
     * Spring injects the retry settings and the app's metrics here after {@code RagConfig} has built the worker with
     * the t09 constructor ({@code RagConfig} is shared code this round, so its call is left as it is). Runs before
     * the context is refreshed, so before the scheduler starts.
     */
    @Autowired
    void use(WorkerRetryProperties retry, IngestMetrics metrics) {
        this.retry = Objects.requireNonNull(retry, "retry");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Claims and handles one batch of jobs, then refreshes the dead-job gauge.
     *
     * @return the number of jobs claimed (handled, released or dead)
     */
    public int pollOnce() {
        return poll().claimed();
    }

    /**
     * For the scheduler: polls again at once while a poll claimed a full batch and every job in it succeeded, so a
     * backlog (the corpus load, a t11 re-index) is not paced by the poll delay; the delay applies once the outbox
     * is drained or a job failed, so a failing embedder is retried once per delay, not in a tight loop (D-48). Each
     * round is a fresh claim with its own lease. A failed claim (database down) is logged, and the next poll tries
     * again.
     *
     * @return the number of polls made
     */
    public int scheduledPoll() {
        int polls = 0;
        try {
            Poll p;
            do {
                p = poll();
                polls++;
            } while (p.claimed() == claimBatch && p.succeeded() == p.claimed());
        } catch (RuntimeException e) {
            log.warn("indexing poll failed: {}", e.toString());
        }
        return polls;
    }

    private record Poll(int claimed, int succeeded) {
    }

    private Poll poll() {
        List<OutboxEvent> jobs = outbox.claimBatch(claimBatch, lease);
        int succeeded = 0;
        for (OutboxEvent job : jobs) if (handle(job)) succeeded++;
        refreshDeadGauge();
        return new Poll(jobs.size(), succeeded);
    }

    private void refreshDeadGauge() {
        try {
            metrics.deadJobs(outbox.countUnresolvedDead());
        } catch (RuntimeException e) {
            log.warn("could not count dead indexing jobs: {}", e.toString());
        }
    }

    private enum Indexed { WRITTEN, STALE }

    /** @return true when the job is done (indexed or skipped), false when it was released or made dead */
    private boolean handle(OutboxEvent job) {
        WorkerRetryProperties settings = retry;
        if (job.attempts() > settings.maxAttempts()) {
            // Earlier claims never reported back (crash, hang past the lease): do not process it yet again.
            fail(job, "no result after " + (job.attempts() - 1) + " claims (lease expired each time)", settings);
            return false;
        }
        long started = System.nanoTime();
        try {
            Indexed indexed = index(job);
            metrics.count(indexed == Indexed.WRITTEN ? Result.INDEXED : Result.STALE_SKIPPED);
            if (!outbox.markDone(job.id(), job.attempts())) {
                log.info("indexing job {} (attempt {}) was claimed again or deleted meanwhile; not marked done here",
                        job.id(), job.attempts());
            } else if (indexed == Indexed.WRITTEN) {
                log.info("indexed document {} in {} ms (job {}, attempt {})", job.documentId(),
                        (System.nanoTime() - started) / 1_000_000, job.id(), job.attempts());
            }
            return true;
        } catch (RuntimeException e) {
            fail(job, errorText(e), settings);
            return false;
        }
    }

    private void fail(OutboxEvent job, String error, WorkerRetryProperties settings) {
        try {
            if (job.attempts() >= settings.maxAttempts()) {
                OutboxStore.DeadLetter dead = outbox.markDead(job.id(), job.attempts(), error);
                if (dead.dead()) {
                    metrics.count(Result.DEAD);
                    log.warn("indexing job {} for document {} is dead after {} attempts (document {}): {}", job.id(),
                            job.documentId(), job.attempts(), dead.documentFailed() ? "failed" : "left as it is",
                            error);
                } else {
                    log.info("indexing job {} (attempt {}) is no longer this worker's; not marked dead", job.id(),
                            job.attempts());
                }
            } else {
                Duration backoff = settings.backoff(job.attempts());
                if (outbox.release(job.id(), job.attempts(), error, backoff)) {
                    metrics.count(Result.RETRIED);
                    log.warn("indexing job {} for document {} failed (attempt {}), retry in {}: {}", job.id(),
                            job.documentId(), job.attempts(), backoff, error);
                } else {
                    log.info("indexing job {} (attempt {}) is no longer this worker's; not released", job.id(),
                            job.attempts());
                }
            }
        } catch (RuntimeException storeFailure) {
            // The lease runs out and the job is claimed again; nothing more to do here.
            log.warn("could not record the failure of indexing job {}: {}", job.id(), storeFailure.toString());
        }
    }

    private Indexed index(OutboxEvent job) {
        Optional<IndexedDocument> found = documents.findById(job.documentId());
        if (found.isEmpty()) {
            log.info("document {} of indexing job {} no longer exists", job.documentId(), job.id());
            return Indexed.STALE;
        }
        IndexedDocument doc = found.get();
        if (!doc.contentHash().equals(job.contentHash())) {
            // A newer PUT has its own job, or the ACL fast path already moved this version on: skip the embedding.
            log.info("indexing job {} is for an older version of document {}; skipped", job.id(), job.documentId());
            return Indexed.STALE;
        }
        List<ChunkText> pieces = chunker.split(doc.title(), doc.content());
        List<float[]> vectors = embed(doc.title(), pieces);
        if (!chunks.replaceForDocument(doc, pieces, vectors)) {
            // A newer PUT committed (or the document was deleted) while this version was embedded (D-48).
            log.info("document {} changed while job {} was indexing it; this version was not written",
                    job.documentId(), job.id());
            return Indexed.STALE;
        }
        return Indexed.WRITTEN;
    }

    /** One embedder call per batch of at most {@code embedBatchSize} chunks, in order. */
    private List<float[]> embed(String title, List<ChunkText> pieces) {
        List<float[]> vectors = new ArrayList<>(pieces.size());
        for (int from = 0; from < pieces.size(); from += embedBatchSize) {
            List<ChunkText> batch = pieces.subList(from, Math.min(pieces.size(), from + embedBatchSize));
            List<String> inputs = batch.stream().map(c -> embeddingText(title, c)).toList();
            List<float[]> out = embedder.embedDocuments(inputs);
            if (out.size() != inputs.size()) {
                throw new IllegalStateException("embedder returned " + out.size() + " vectors for " + inputs.size()
                        + " chunks");
            }
            for (float[] v : out) {
                if (v.length != embedder.dimensions()) {
                    throw new IllegalStateException("embedder returned a vector of " + v.length + " values, expected "
                            + embedder.dimensions());
                }
            }
            vectors.addAll(out);
        }
        return vectors;
    }

    /** The text embedded for a chunk (D-47): the title, so a chunk that says "it" lands near its subject. */
    static String embeddingText(String title, ChunkText chunk) {
        return title + "\n\n" + chunk.text();
    }

    private static String errorText(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getClass().getSimpleName() + ": "
                + e.getMessage();
        return message.length() <= MAX_ERROR_CHARS ? message : message.substring(0, MAX_ERROR_CHARS) + "...";
    }
}
