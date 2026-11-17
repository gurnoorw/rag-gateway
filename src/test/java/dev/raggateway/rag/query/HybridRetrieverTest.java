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
import dev.raggateway.rag.query.ChunkSearchRepository.Row;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link HybridRetriever} with test doubles for the legs and the embedder: concurrency, modes, failures, metrics. */
class HybridRetrieverTest {

    static final String TENANT = "t1";

    final FakeRepository repository = new FakeRepository();
    final CountingEmbedder embedder = new CountingEmbedder();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final HybridRetriever retriever = new HybridRetriever(repository, embedder, RagProperties.defaults(), meters);

    @AfterEach
    void close() {
        retriever.close();
    }

    static RetrievalQuery query(RetrievalMode mode, Set<String> groups) {
        return new RetrievalQuery(TENANT, "k8s", "how do retries work", groups, 5, mode);
    }

    static Row row(String id) {
        return new Row(id, "doc-" + id, "Title " + id, 0, "text " + id);
    }

    @Test
    void ac_t10_5_theLegsRunConcurrently() {
        repository.keywordDelay = Duration.ofMillis(300);
        repository.vectorDelay = Duration.ofMillis(300);
        retriever.retrieve(query(RetrievalMode.HYBRID, Set.of("eng")));    // warm up the executor and classes

        long start = System.nanoTime();
        RetrievalResult result = retriever.retrieve(query(RetrievalMode.HYBRID, Set.of("eng")));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).isLessThan(500);
        assertThat(result.modeUsed()).isEqualTo(RetrievalMode.HYBRID);
    }

    @Test
    void theKeywordLegDoesNotWaitForTheEmbedding() {
        embedder.delay = Duration.ofMillis(300);
        repository.keywordDelay = Duration.ofMillis(300);

        long start = System.nanoTime();
        retriever.retrieve(query(RetrievalMode.HYBRID, Set.of("eng")));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(millis).isLessThan(500);
    }

    @Test
    void ac_t10_10_theModeDecidesWhichLegsRun() {
        retriever.retrieve(query(RetrievalMode.KEYWORD, Set.of("eng")));
        assertThat(repository.keywordCalls).hasValue(1);
        assertThat(repository.vectorCalls).hasValue(0);
        assertThat(embedder.queryCalls).hasValue(0);

        retriever.retrieve(query(RetrievalMode.VECTOR, Set.of("eng")));
        assertThat(repository.keywordCalls).hasValue(1);
        assertThat(repository.vectorCalls).hasValue(1);
        assertThat(embedder.queryCalls).hasValue(1);

        retriever.retrieve(query(RetrievalMode.HYBRID, Set.of("eng")));
        assertThat(repository.keywordCalls).hasValue(2);
        assertThat(repository.vectorCalls).hasValue(2);
        assertThat(embedder.queryCalls).hasValue(2);
        assertThat(embedder.documentCalls).hasValue(0);   // a question is never embedded as a document
    }

    @Test
    void ac_t10_12_hybridWithTheEmbedderDownReturnsKeywordResultsAndSaysSo() {
        embedder.failure = () -> new IllegalStateException("connection to Ollama refused");
        repository.keywordRows = List.of(row("k1"), row("k2"));

        RetrievalResult result = retriever.retrieve(query(RetrievalMode.HYBRID, Set.of("eng")));

        assertThat(result.modeUsed()).isEqualTo(RetrievalMode.KEYWORD);
        assertThat(result.degraded(query(RetrievalMode.HYBRID, Set.of("eng")))).isTrue();
        assertThat(result.chunks()).extracting(RetrievedChunk::chunkId).containsExactly("k1", "k2");
        assertThat(result.chunks()).allMatch(c -> c.vectorRank() == 0);
        assertThat(repository.vectorCalls).hasValue(0);
        assertThat(meters.get(MetricNames.RETRIEVAL_CANDIDATES).tag("leg", "vector").summary().totalAmount()).isZero();
        assertThat(meters.get(MetricNames.RETRIEVAL_CANDIDATES).tag("leg", "keyword").summary().totalAmount())
                .isEqualTo(2);
    }

    @Test
    void ac_t10_12_vectorModeWithTheEmbedderDownFailsWith503() {
        embedder.failure = () -> new IllegalStateException("connection to Ollama refused");

        assertThatThrownBy(() -> retriever.retrieve(query(RetrievalMode.VECTOR, Set.of("eng"))))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.ALL_PROVIDERS_FAILED);
                    assertThat(e.code().httpStatus()).isEqualTo(503);
                });
        assertThat(repository.vectorCalls).hasValue(0);
    }

    @Test
    void aDatabaseFailureInEitherLegIsAnErrorInHybridMode() {
        repository.keywordFailure = () -> new DataAccessResourceFailureException("keyword down");
        assertThatThrownBy(() -> retriever.retrieve(query(RetrievalMode.HYBRID, Set.of("eng"))))
                .isInstanceOf(DataAccessResourceFailureException.class).hasMessage("keyword down");

        repository.keywordFailure = null;
        repository.vectorFailure = () -> new DataAccessResourceFailureException("vector down");
        assertThatThrownBy(() -> retriever.retrieve(query(RetrievalMode.HYBRID, Set.of("eng"))))
                .isInstanceOf(DataAccessResourceFailureException.class).hasMessage("vector down");
    }

    @Test
    void emptyGroupsReturnNothingWithoutSqlOrAnEmbedderCall() {
        for (RetrievalMode mode : RetrievalMode.values()) {
            RetrievalResult result = retriever.retrieve(query(mode, Set.of()));
            assertThat(result.chunks()).isEmpty();
            assertThat(result.modeUsed()).isEqualTo(mode);
        }
        assertThat(repository.keywordCalls).hasValue(0);
        assertThat(repository.vectorCalls).hasValue(0);
        assertThat(embedder.queryCalls).hasValue(0);
    }

    @Test
    void fusedChunksCarryTheirRanksScoresAndRowFieldsAndStopAtTopK() {
        repository.keywordRows = List.of(row("a"), row("b"), row("c"), row("d"));
        repository.vectorRows = List.of(row("c"), row("a"), row("e"), row("b"), row("f"), row("g"));

        List<RetrievedChunk> chunks = retriever.retrieve(query(RetrievalMode.HYBRID, Set.of("eng"))).chunks();

        assertThat(chunks).extracting(RetrievedChunk::chunkId).containsExactly("a", "c", "b", "e", "d");
        assertThat(chunks.getFirst()).isEqualTo(
                new RetrievedChunk("a", "doc-a", "Title a", 0, "text a", 1.0 / 61 + 1.0 / 62, 1, 2));
    }

    @Test
    void singleLegModesUseTheSameFormulaOverOneList() {
        repository.keywordRows = List.of(row("a"), row("b"));
        List<RetrievedChunk> chunks = retriever.retrieve(query(RetrievalMode.KEYWORD, Set.of("eng"))).chunks();
        assertThat(chunks).extracting(RetrievedChunk::score).containsExactly(1.0 / 61, 1.0 / 62);
        assertThat(chunks).extracting(RetrievedChunk::keywordRank).containsExactly(1, 2);
        assertThat(chunks).extracting(RetrievedChunk::vectorRank).containsOnly(0);
    }

    @Test
    void theLegsAskForTheConfiguredCandidateCountAndRecordBoundedMetrics() {
        retriever.retrieve(query(RetrievalMode.HYBRID, Set.of("eng")));

        assertThat(repository.limits).containsOnly(50);
        for (String leg : List.of("keyword", "embed", "vector")) {
            assertThat(meters.get(MetricNames.RETRIEVAL_LEG_DURATION).tag("leg", leg).timer().count()).isEqualTo(1);
        }
        assertThat(meters.get(MetricNames.RETRIEVAL_DURATION).tag("mode", "hybrid").timer().count()).isEqualTo(1);
        meters.getMeters().forEach(m -> assertThat(m.getId().getTags())
                .allSatisfy(t -> assertThat(MetricNames.ALLOWED_LABELS).contains(t.getKey())));
    }

    /** Legs with fixed rows, optional delays and failures, and call counts. Never touches a database. */
    static final class FakeRepository extends ChunkSearchRepository {
        volatile List<Row> keywordRows = List.of(row("k"));
        volatile List<Row> vectorRows = List.of(row("v"));
        volatile Duration keywordDelay = Duration.ZERO;
        volatile Duration vectorDelay = Duration.ZERO;
        volatile Supplier<RuntimeException> keywordFailure;
        volatile Supplier<RuntimeException> vectorFailure;
        final AtomicInteger keywordCalls = new AtomicInteger();
        final AtomicInteger vectorCalls = new AtomicInteger();
        final List<Integer> limits = java.util.Collections.synchronizedList(new ArrayList<>());

        FakeRepository() {
            super(JdbcClient.create(new PGSimpleDataSource()), new DataSourceTransactionManager(new PGSimpleDataSource()),
                    RagProperties.defaults());
        }

        @Override
        public List<Row> keyword(String tenant, String collection, Set<String> groups, String question, int limit) {
            keywordCalls.incrementAndGet();
            limits.add(limit);
            sleep(keywordDelay);
            if (keywordFailure != null) throw keywordFailure.get();
            return keywordRows;
        }

        @Override
        public List<Row> vector(String tenant, String collection, Set<String> groups, float[] vector, int limit) {
            vectorCalls.incrementAndGet();
            limits.add(limit);
            sleep(vectorDelay);
            if (vectorFailure != null) throw vectorFailure.get();
            return vectorRows;
        }
    }

    static final class CountingEmbedder implements Embedder {
        final AtomicInteger queryCalls = new AtomicInteger();
        final AtomicInteger documentCalls = new AtomicInteger();
        volatile Duration delay = Duration.ZERO;
        volatile Supplier<RuntimeException> failure;

        @Override
        public int dimensions() {
            return 768;
        }

        @Override
        public List<float[]> embedDocuments(List<String> texts) {
            documentCalls.incrementAndGet();
            return texts.stream().map(t -> new float[768]).toList();
        }

        @Override
        public float[] embedQuery(String text) {
            queryCalls.incrementAndGet();
            sleep(delay);
            if (failure != null) throw failure.get();
            float[] v = new float[768];
            v[0] = 1;
            return v;
        }
    }

    static void sleep(Duration d) {
        if (d.isZero()) return;
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
