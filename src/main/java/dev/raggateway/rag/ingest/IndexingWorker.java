package dev.raggateway.rag.ingest;

import dev.raggateway.rag.ChunkText;
import dev.raggateway.rag.Chunker;
import dev.raggateway.rag.Embedder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns outbox jobs into chunk rows (t09, LLD "Indexing worker and the outbox", DECISIONS.md C4, D-47).
 *
 * <p>{@link #pollOnce()} claims at most {@code claimBatch} jobs (default 4) with one lease (default 5 min) and
 * handles them one at a time:
 * <ol>
 *   <li>read the document ({@link DocumentStore#findById}: one short connection);</li>
 *   <li>chunk it;</li>
 *   <li>embed {@code <title>\n\n<chunk text>} in batches of at most {@code embedBatchSize} chunks (default 20), one
 *       embedder call per batch. No transaction is open and no connection is held here: this class is not
 *       transactional, and every store call returns its connection before the next step (AC-t09-15);</li>
 *   <li>{@link ChunkStore#replaceForDocument}: one transaction that swaps the chunks and sets status and count;</li>
 *   <li>{@link OutboxStore#markDone}.</li>
 * </ol>
 * A job that fails at any step is released back to PENDING with the error (t09); t11 adds the attempt limit,
 * dead jobs and the stale-job skip. A document that no longer exists ends its job as done.
 *
 * <p>In the app one scheduled poller per instance calls {@link #pollOnce()} with a fixed delay, so polls never
 * overlap ({@code RagConfig}, off under the test profile). Tests call {@link #pollOnce()} directly.
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

    public IndexingWorker(OutboxStore outbox, DocumentStore documents, ChunkStore chunks, Chunker chunker,
                          Embedder embedder, int claimBatch, Duration lease, int embedBatchSize) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.chunks = Objects.requireNonNull(chunks, "chunks");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.lease = Objects.requireNonNull(lease, "lease");
        if (claimBatch <= 0 || embedBatchSize <= 0) throw new IllegalArgumentException("batch sizes must be > 0");
        this.claimBatch = claimBatch;
        this.embedBatchSize = embedBatchSize;
    }

    /**
     * Claims and handles one batch of jobs.
     *
     * @return the number of jobs claimed (handled or released)
     */
    public int pollOnce() {
        List<OutboxEvent> jobs = outbox.claimBatch(claimBatch, lease);
        for (OutboxEvent job : jobs) handle(job);
        return jobs.size();
    }

    /** For the scheduler: a failed claim (database down) is logged, and the next poll tries again. */
    public void scheduledPoll() {
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.warn("indexing poll failed: {}", e.toString());
        }
    }

    private void handle(OutboxEvent job) {
        long started = System.nanoTime();
        try {
            int count = index(job);
            outbox.markDone(job.id());
            log.info("indexed document {} into {} chunks in {} ms (job {}, attempt {})", job.documentId(), count,
                    (System.nanoTime() - started) / 1_000_000, job.id(), job.attempts());
        } catch (RuntimeException e) {
            String error = errorText(e);
            log.warn("indexing job {} for document {} failed (attempt {}): {}", job.id(), job.documentId(),
                    job.attempts(), error);
            try {
                outbox.release(job.id(), error);
            } catch (RuntimeException releaseFailure) {
                // The lease runs out and the job is claimed again; nothing more to do here.
                log.warn("could not release indexing job {}: {}", job.id(), releaseFailure.toString());
            }
        }
    }

    /** @return the number of chunks written; 0 when the document is gone */
    private int index(OutboxEvent job) {
        Optional<IndexedDocument> found = documents.findById(job.documentId());
        if (found.isEmpty()) {
            log.info("document {} of indexing job {} no longer exists", job.documentId(), job.id());
            return 0;
        }
        IndexedDocument doc = found.get();
        List<ChunkText> pieces = chunker.split(doc.title(), doc.content());
        List<float[]> vectors = embed(doc.title(), pieces);
        chunks.replaceForDocument(doc, pieces, vectors);
        return pieces.size();
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
