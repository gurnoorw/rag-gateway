package dev.raggateway.rag.ingest;

import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.rag.ChunkText;
import dev.raggateway.rag.Chunker;
import dev.raggateway.rag.Embedder;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.ingest.IngestFakes.ChunkRow;
import dev.raggateway.rag.ingest.IngestFakes.CountingEmbedder;
import dev.raggateway.rag.ingest.IngestFakes.JobStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The indexing worker on in-memory stores and a counting fake embedder, driven by {@link IndexingWorker#pollOnce()}
 * (t09, D-47). The JDBC chunk store and the no-connection-while-embedding check are in
 * {@code JdbcChunkStoreTest} and {@code IndexingWorkerConnectionTest}; the scheduled worker end to end is
 * {@code IngestionE2EIT}.
 */
class IndexingWorkerTest {

    static final RagProperties RAG = RagProperties.defaults();

    final IngestFakes fakes = new IngestFakes();
    final FakeEmbedder fake = new FakeEmbedder();
    final CountingEmbedder embedder = fakes.new CountingEmbedder(fake);
    final Chunker chunker = new HeadingParagraphSentenceChunker(new CharRatioTokenEstimator(),
            RAG.chunk().targetTokens(), RAG.chunk().overlapTokens(), RAG.chunk().maxTokens());
    final IndexingWorker worker = new IndexingWorker(fakes.outbox, fakes.documents, fakes.chunks, chunker, embedder,
            RAG.worker().claimBatch(), RAG.worker().lease(), RAG.embedBatchSize());

    @Test
    void d47Defaults() {
        assertThat(RAG.worker().claimBatch()).isEqualTo(4);
        assertThat(RAG.worker().lease()).isEqualTo(Duration.ofMinutes(5));
        assertThat(RAG.worker().pollDelay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(RAG.embedBatchSize()).isEqualTo(20);
        assertThat(RAG.chunk()).isEqualTo(new RagProperties.Chunking(400, 50, 400));
    }

    @Test
    void indexesAQueuedDocument() {
        UUID id = fakes.put("t1", "docs", "concepts.jobs", "Jobs", "# Jobs\n\nA job runs to completion.\n", "eng");

        assertThat(worker.pollOnce()).isEqualTo(1);

        IndexedDocument doc = fakes.documents.get(id);
        assertThat(doc.status()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(doc.chunkCount()).isEqualTo(1);
        assertThat(fakes.outbox.jobFor(id).status).isEqualTo(JobStatus.DONE);
        ChunkRow row = fakes.chunks.rows(id).getFirst();
        assertThat(row.text()).isEqualTo("# Jobs\n\nA job runs to completion.");
        // Embedded with the title (D-47); the prefix is the embedder's job (the Ollama adapter adds it).
        assertThat(embedder.calls).containsExactly(List.of("Jobs\n\n# Jobs\n\nA job runs to completion."));
        assertThat(row.embedding()).containsExactly(fake.vector("Jobs\n\n# Jobs\n\nA job runs to completion."));
        assertThat(worker.pollOnce()).isZero();
    }

    @Test
    void ac_t09_4_chunksCarryTheDocumentsTenantCollectionAndGroups() {
        UUID id = fakes.put("t-acme", "handbook", "setup.install", "Install", section(3), "eng", "finance");

        worker.pollOnce();

        assertThat(fakes.chunks.rows(id)).hasSize(3).allSatisfy(r -> {
            assertThat(r.tenantId()).isEqualTo("t-acme");
            assertThat(r.collection()).isEqualTo("handbook");
            assertThat(r.docId()).isEqualTo("setup.install");
            assertThat(r.title()).isEqualTo("Install");
            assertThat(r.aclGroups()).isEqualTo(Set.of("eng", "finance"));
        });
    }

    @Test
    void ac_t09_7_oneEmbedCallPerBatchOfAtMostTwenty() {
        UUID id = fakes.put("t1", "docs", "big", "Big", section(45), "eng");

        worker.pollOnce();

        assertThat(fakes.chunks.rows(id)).hasSize(45);
        assertThat(embedder.batchSizes()).containsExactly(20, 20, 5);
        // Order is kept across batches: chunk i got the vector of input i.
        List<ChunkRow> rows = fakes.chunks.rows(id);
        for (int i = 0; i < rows.size(); i++) {
            assertThat(rows.get(i).ordinal()).isEqualTo(i);
            assertThat(rows.get(i).embedding()).containsExactly(fake.vector("Big\n\n" + rows.get(i).text()));
        }
    }

    @Test
    void claimsAtMostFourJobsWithAFiveMinuteLeaseAndHandlesThemOneAtATime() {
        List<UUID> ids = IntStream.range(0, 6)
                .mapToObj(i -> fakes.put("t1", "docs", "d" + i, "T" + i, "Text " + i + ".", "eng")).toList();

        assertThat(worker.pollOnce()).isEqualTo(4);
        assertThat(fakes.outbox.claimMax).containsExactly(4);
        assertThat(fakes.outbox.claimLease).containsExactly(Duration.ofMinutes(5));

        // Strictly sequential: each job is read, embedded, swapped and marked done before the next is read.
        List<String> expected = new java.util.ArrayList<>(List.of("claim 4"));
        for (int i = 0; i < 4; i++) {
            UUID doc = ids.get(i);
            expected.addAll(List.of("find " + doc, "embed 1", "replace " + doc, "done " + fakes.outbox.jobFor(doc).id));
        }
        assertThat(fakes.events).containsExactlyElementsOf(expected);

        assertThat(worker.pollOnce()).isEqualTo(2);
        assertThat(ids).allSatisfy(id -> assertThat(fakes.documents.get(id).status()).isEqualTo(DocumentStatus.INDEXED));
    }

    @Test
    void aFailedJobIsReleasedWithTheErrorAndTheOthersContinue() {
        UUID ok1 = fakes.put("t1", "docs", "ok1", "Ok", "First.", "eng");
        UUID bad = fakes.put("t1", "docs", "bad", "Bad", "Second.", "eng");
        UUID ok2 = fakes.put("t1", "docs", "ok2", "Ok", "Third.", "eng");
        embedder.failWhen = inputs -> inputs.getFirst().startsWith("Bad");

        assertThat(worker.pollOnce()).isEqualTo(3);

        IngestFakes.Job job = fakes.outbox.jobFor(bad);
        assertThat(job.status).isEqualTo(JobStatus.PENDING);
        assertThat(job.lastError).isEqualTo("EmbeddingException: Ollama embed returned HTTP 500");
        assertThat(fakes.documents.get(bad).status()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(fakes.chunks.rows(bad)).isEmpty();
        assertThat(fakes.documents.get(ok1).status()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(fakes.documents.get(ok2).status()).isEqualTo(DocumentStatus.INDEXED);

        embedder.failWhen = inputs -> false;   // Ollama is back: the released job is retried once its backoff is over
        assertThat(fakes.outbox.retries).containsExactly(Duration.ofSeconds(30));
        assertThat(worker.pollOnce()).isZero();
        fakes.advance(Duration.ofSeconds(30));
        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(job.status).isEqualTo(JobStatus.DONE);
        assertThat(job.attempts).isEqualTo(2);
        assertThat(fakes.documents.get(bad).status()).isEqualTo(DocumentStatus.INDEXED);
    }

    @Test
    void aFailedSwapIsReleasedAndLeavesTheOldChunks() {
        UUID id = fakes.put("t1", "docs", "d", "T", "Old text.", "eng");
        worker.pollOnce();
        List<ChunkRow> old = fakes.chunks.rows(id);
        fakes.put("t1", "docs", "d", "T", "New text.", "eng");
        fakes.chunks.beforeReplace = d -> {
            throw new IllegalStateException("database is down");
        };

        worker.pollOnce();

        assertThat(fakes.chunks.rows(id)).isEqualTo(old);
        assertThat(fakes.outbox.jobs.values()).anySatisfy(j -> {
            assertThat(j.status).isEqualTo(JobStatus.PENDING);
            assertThat(j.lastError).isEqualTo("IllegalStateException: database is down");
        });
    }

    @Test
    void aVersionSupersededDuringEmbeddingIsNotWrittenAndTheNewerOneIs() {
        UUID id = fakes.put("t1", "docs", "d", "T", "Old text.", "eng");
        fakes.chunks.beforeReplace = d -> {
            fakes.chunks.beforeReplace = x -> { };
            fakes.put("t1", "docs", "d", "T", "New text.", "eng");   // commits while "Old text." is embedded
        };

        worker.pollOnce();
        assertThat(fakes.chunks.rows(id)).isEmpty();
        assertThat(fakes.documents.get(id).status()).isEqualTo(DocumentStatus.QUEUED);

        worker.pollOnce();
        assertThat(fakes.chunks.rows(id)).extracting(ChunkRow::text).containsExactly("New text.");
        assertThat(fakes.documents.get(id).status()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(fakes.outbox.jobs.values()).allSatisfy(j -> assertThat(j.status).isEqualTo(JobStatus.DONE));
    }

    @Test
    void anEmbedderThatReturnsTheWrongCountFailsTheJob() {
        UUID id = fakes.put("t1", "docs", "d", "T", "Text.", "eng");
        var broken = new IndexingWorker(fakes.outbox, fakes.documents, fakes.chunks, chunker, new Embedder() {
            @Override
            public int dimensions() {
                return 768;
            }

            @Override
            public List<float[]> embedDocuments(List<String> texts) {
                return List.of();
            }

            @Override
            public float[] embedQuery(String text) {
                return new float[768];
            }
        }, 4, Duration.ofMinutes(5), 20);

        broken.pollOnce();

        assertThat(fakes.outbox.jobFor(id).status).isEqualTo(JobStatus.PENDING);
        assertThat(fakes.outbox.jobFor(id).lastError).contains("0 vectors for 1 chunks");
        assertThat(fakes.chunks.rows(id)).isEmpty();
    }

    @Test
    void aJobWhoseDocumentIsGoneIsDone() {
        UUID ghost = UUID.randomUUID();
        UUID job = fakes.outbox.insert(ghost, "0".repeat(64));

        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(fakes.outbox.job(job).status).isEqualTo(JobStatus.DONE);
        assertThat(embedder.calls).isEmpty();
    }

    @Test
    void reindexingReplacesTheChunks() {
        UUID id = fakes.put("t1", "docs", "d", "T", section(3), "eng");
        worker.pollOnce();
        fakes.put("t1", "docs", "d", "T", section(2), "eng");

        worker.pollOnce();

        assertThat(fakes.chunks.rows(id)).hasSize(2);
        assertThat(fakes.documents.get(id).chunkCount()).isEqualTo(2);
    }

    @Test
    void scheduledPollDrainsABacklogWithoutWaitingForTheDelay() {
        for (int i = 0; i < 9; i++) fakes.put("t1", "docs", "d" + i, "T", "Text " + i + ".", "eng");

        assertThat(worker.scheduledPoll()).isEqualTo(3);   // 4 + 4 + 1: stops at the first batch that is not full

        assertThat(fakes.outbox.jobs.values()).hasSize(9)
                .allSatisfy(j -> assertThat(j.status).isEqualTo(JobStatus.DONE));
    }

    @Test
    void scheduledPollStopsAfterAFailedJobSoAFailingEmbedderIsNotRetriedInALoop() {
        for (int i = 0; i < 8; i++) fakes.put("t1", "docs", "d" + i, "T", "Text " + i + ".", "eng");
        embedder.failWhen = inputs -> true;

        assertThat(worker.scheduledPoll()).isEqualTo(1);

        assertThat(fakes.outbox.jobs.values()).filteredOn(j -> j.status == JobStatus.PENDING).hasSize(8);
    }

    @Test
    void scheduledPollSurvivesAFailedClaim() {
        var down = new IndexingWorker(new OutboxStore() {
            @Override
            public UUID insert(UUID documentId, String contentHash) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<OutboxEvent> claimBatch(int max, Duration lease) {
                throw new IllegalStateException("connection refused");
            }

            @Override
            public boolean markDone(UUID id, int attempt) {
                return false;
            }

            @Override
            public boolean release(UUID id, int attempt, String error, Duration retryAfter) {
                return false;
            }

            @Override
            public DeadLetter markDead(UUID id, int attempt, String error) {
                return new DeadLetter(false, false);
            }

            @Override
            public long countUnresolvedDead() {
                throw new IllegalStateException("connection refused");
            }
        }, fakes.documents, fakes.chunks, chunker, embedder, 4, Duration.ofMinutes(5), 20);

        down.scheduledPoll();   // logs and returns; the next poll tries again
    }

    @Test
    void embeddingTextIsTitleThenChunk() {
        assertThat(IndexingWorker.embeddingText("Jobs", new ChunkText(0, "Body.", 2))).isEqualTo("Jobs\n\nBody.");
    }

    /** A document of {@code n} short sections: the chunker makes exactly one chunk per heading. */
    static String section(int n) {
        return IntStream.range(0, n).mapToObj(i -> "## Section " + i + "\n\nSentence number " + i + ".")
                .collect(Collectors.joining("\n\n"));
    }
}
