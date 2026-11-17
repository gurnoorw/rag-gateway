package dev.raggateway.rag.ingest;

import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.rag.ingest.IngestFakes.CountingEmbedder;
import dev.raggateway.rag.ingest.IngestFakes.Job;
import dev.raggateway.rag.ingest.IngestFakes.JobStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * t11 worker rules on in-memory stores and the fake clock of {@link IngestFakes} (no sleeps): stale-job skip before
 * embedding, retries with growing backoff, dead jobs after the attempt limit, the document's failed status, the
 * poison-job guard and the counters and gauge (D-51). The same rules on the real database, through HTTP, are in
 * {@link IncrementalIndexingIT}.
 */
class IndexingWorkerRetryTest {

    final IngestFakes fakes = new IngestFakes();
    final CountingEmbedder embedder = fakes.new CountingEmbedder(new FakeEmbedder());
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final IngestMetrics metrics = new IngestMetrics(registry);
    final IndexingWorker worker = worker(WorkerRetryProperties.defaults());
    final DocumentService service = new DocumentService(fakes.documents, fakes.outbox);

    IndexingWorker worker(WorkerRetryProperties retry) {
        return new IndexingWorker(fakes.outbox, fakes.documents, fakes.chunks,
                new HeadingParagraphSentenceChunker(new CharRatioTokenEstimator(), 400, 50, 400), embedder, 4,
                Duration.ofMinutes(5), 20, retry, metrics);
    }

    double count(String result) {
        var c = registry.find(MetricNames.INGEST_DOCUMENTS).tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    double deadGauge() {
        return registry.get(MetricNames.OUTBOX_DEAD).gauge().value();
    }

    UUID put(String content) {
        return service.upsert(new DocumentUpsert("t1", "docs", "d", "T", content, Set.of("eng"))).documentId();
    }

    @Test
    void theGaugeIsRegisteredAtOnceAndACounterAtItsFirstEvent() {
        assertThat(deadGauge()).isZero();
        assertThat(registry.find(MetricNames.INGEST_DOCUMENTS).counters()).isEmpty();
        metrics.count(UpsertResult.Outcome.ACL_UPDATED);
        assertThat(count("acl_updated")).isEqualTo(1);
        assertThat(IngestMetrics.Result.values()).extracting(IngestMetrics.Result::label).containsExactly(
                "queued", "unchanged", "acl_updated", "indexed", "stale_skipped", "retried", "dead", "deleted");
    }

    @Test
    void aStaleJobIsSkippedBeforeEmbedding() {
        UUID id = put("Version one.");
        put("Version two.");                                  // v1's job is now stale

        assertThat(worker.pollOnce()).isEqualTo(2);

        assertThat(embedder.calls).hasSize(1);                // only v2 was embedded
        assertThat(embedder.calls.getFirst().getFirst()).contains("Version two.");
        assertThat(fakes.chunks.rows(id)).extracting(IngestFakes.ChunkRow::text).containsExactly("Version two.");
        assertThat(fakes.outbox.jobsFor(id)).allSatisfy(j -> assertThat(j.status).isEqualTo(JobStatus.DONE));
        assertThat(count("stale_skipped")).isEqualTo(1);
        assertThat(count("indexed")).isEqualTo(1);
    }

    @Test
    void retriesWithGrowingBackoffThenDeadAfterFiveAttemptsAndTheDocumentIsFailed() {
        UUID id = put("Always fails.");
        embedder.failWhen = inputs -> true;
        Job job = fakes.outbox.jobFor(id);

        Duration[] expected = {Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4)};
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(worker.pollOnce()).isEqualTo(1);
            Duration backoff = expected[attempt - 1];
            assertThat(job.status).isEqualTo(JobStatus.PENDING);
            assertThat(job.leaseUntil).isEqualTo(fakes.now.plus(backoff));
            // Not claimable a moment before its retry time; claimable at it.
            fakes.advance(backoff.minusMillis(1));
            assertThat(worker.pollOnce()).isZero();
            fakes.advance(Duration.ofMillis(1));
        }
        assertThat(fakes.outbox.retries).containsExactly(expected);
        assertThat(count("retried")).isEqualTo(4);
        assertThat(deadGauge()).isZero();

        assertThat(worker.pollOnce()).isEqualTo(1);           // attempt 5: the limit

        assertThat(job.status).isEqualTo(JobStatus.DEAD);
        assertThat(job.attempts).isEqualTo(5);
        assertThat(job.lastError).contains("HTTP 500");
        assertThat(fakes.documents.get(id).status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(count("dead")).isEqualTo(1);
        assertThat(deadGauge()).isEqualTo(1);
        fakes.advance(Duration.ofHours(1));
        assertThat(worker.pollOnce()).isZero();               // never claimed again

        // A re-PUT of the same content queues it again (the way a dead job is retried); the alert clears.
        embedder.failWhen = inputs -> false;
        assertThat(service.upsert(new DocumentUpsert("t1", "docs", "d", "T", "Always fails.", Set.of("eng"))).outcome())
                .isEqualTo(UpsertResult.Outcome.QUEUED);
        assertThat(worker.pollOnce()).isEqualTo(1);
        assertThat(fakes.documents.get(id).status()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(deadGauge()).isZero();
    }

    @Test
    void aDeadJobLeavesADocumentThatANewerPutOwns() {
        UUID id = put("Old.");
        var oneShot = worker(new WorkerRetryProperties(1, Duration.ofSeconds(30), Duration.ofMinutes(10)));
        embedder.failWhen = inputs -> true;
        embedder.duringCall = () -> fakes.documents.upsert(
                new DocumentUpsert("t1", "docs", "d", "T", "New.", Set.of("eng")));   // a PUT lands mid-embed

        oneShot.pollOnce();

        assertThat(fakes.outbox.jobFor(id).status).isEqualTo(JobStatus.DEAD);
        assertThat(fakes.documents.get(id).status()).isEqualTo(DocumentStatus.QUEUED);   // not failed
    }

    @Test
    void maxAttemptsAndBackoffComeFromTheSettings() {
        UUID id = put("Fails.");
        embedder.failWhen = inputs -> true;
        var two = worker(new WorkerRetryProperties(2, Duration.ofSeconds(1), Duration.ofSeconds(1)));

        two.pollOnce();
        assertThat(fakes.outbox.retries).containsExactly(Duration.ofSeconds(1));
        fakes.advance(Duration.ofSeconds(1));
        two.pollOnce();

        assertThat(fakes.outbox.jobFor(id).status).isEqualTo(JobStatus.DEAD);
        assertThat(fakes.outbox.jobFor(id).attempts).isEqualTo(2);
    }

    @Test
    void aJobWhoseLeaseRanOutOnEveryAttemptIsMadeDeadWithoutBeingProcessedAgain() {
        UUID id = put("Kills the worker.");
        Job job = fakes.outbox.jobFor(id);
        // Five claims that never reported back (the worker died each time): IN_PROGRESS with an expired lease.
        job.attempts = 5;
        job.status = JobStatus.IN_PROGRESS;
        job.leaseUntil = fakes.now.minusSeconds(1);

        assertThat(worker.pollOnce()).isEqualTo(1);

        assertThat(embedder.calls).isEmpty();
        assertThat(job.status).isEqualTo(JobStatus.DEAD);
        assertThat(job.attempts).isEqualTo(6);
        assertThat(job.lastError).contains("lease expired");
        assertThat(fakes.documents.get(id).status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(count("dead")).isEqualTo(1);
    }

    @Test
    void aWorkerWhoseLeaseRanOutDoesNotReleaseOrFinishTheNewOwnersJob() {
        UUID id = put("Slow.");
        Job job = fakes.outbox.jobFor(id);
        embedder.failWhen = inputs -> true;
        embedder.duringCall = () -> {
            // While this worker embeds, its lease runs out and another worker claims the job.
            embedder.duringCall = () -> { };
            fakes.advance(Duration.ofMinutes(6));
            assertThat(fakes.outbox.claimBatch(1, Duration.ofMinutes(5))).hasSize(1);
        };

        worker.pollOnce();

        assertThat(job.status).isEqualTo(JobStatus.IN_PROGRESS);   // the late release changed nothing
        assertThat(job.attempts).isEqualTo(2);
        assertThat(job.lastError).isNull();
        assertThat(fakes.outbox.retries).isEmpty();
        assertThat(count("retried")).isZero();
    }
}
