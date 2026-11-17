package dev.raggateway.eval;

import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalResult;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.Retriever;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.rag.query.AclAudit;
import dev.raggateway.rag.query.ChunkSearchRepository;
import dev.raggateway.rag.query.HybridRetriever;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.TestData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The eval indexer and both runners on Postgres with a small hand-made collection (t13): what the indexer copies,
 * that a second run reuses the index and repeats the metrics (AC-t13-9), and the ACL eval's leak count and
 * authorised recall (AC-t13-10). Keyword mode, so the fake embedder's vectors are never compared.
 */
class EvalRetrievalDbTest extends AbstractPostgresTest {

    static final FakeEmbedder FAKE = new FakeEmbedder();
    final String source = "t-evalsrc-" + UUID.randomUUID();
    final String target = "t-eval-" + UUID.randomUUID();

    static final String ALPHA = "The flux capacitor needs exactly 1.21 gigawatts of power to start.";
    static final String BETA = "The quantum widget stores marmalade in a locked vault.";

    final EvalSet set = new EvalSet("questions.v1", List.of(
            new EvalSet.Question("q01", EvalSet.Kind.CONCEPTUAL, "How much power does the flux capacitor need?",
                    List.of(new EvalSet.Gold("setup.alpha", ALPHA)), Set.of()),
            new EvalSet.Question("q02", EvalSet.Kind.EXACT_TERM, "Where is the quantum widget marmalade kept?",
                    List.of(new EvalSet.Gold("concepts.beta", BETA)), Set.of()),
            new EvalSet.Question("q03", EvalSet.Kind.CONCEPTUAL, "Xylophone zebra quokka?",
                    List.of(new EvalSet.Gold("tutorials.gamma", "Penguins enjoy cold water and fresh fish.")), Set.of()),
            new EvalSet.Question("q04", EvalSet.Kind.UNANSWERABLE, "How long do penguins swim each day?", List.of(),
                    Set.of()),
            new EvalSet.Question("q05", EvalSet.Kind.ACL, "How much power does the flux capacitor need?",
                    List.of(new EvalSet.Gold("setup.alpha", ALPHA)), Set.of("finance"))));

    EvalIndexer indexer;
    HybridRetriever retriever;

    @BeforeEach
    void setUp() {
        TestData.insertTenant(jdbc, source, 1000, BigDecimal.TEN, Set.of());
        RagFixtures.document(jdbc, source, "k8s-docs", "setup.alpha", "Alpha", Set.of("eng", "finance"), FAKE,
                "# Alpha", ALPHA + " It is kept in the lab.");
        RagFixtures.document(jdbc, source, "k8s-docs", "concepts.beta", "Beta", Set.of("eng"), FAKE, BETA);
        RagFixtures.document(jdbc, source, "k8s-docs", "tutorials.gamma", "Gamma", Set.of("eng", "support"), FAKE,
                "Penguins enjoy cold water and fresh fish.");
        RagFixtures.document(jdbc, source, "k8s-docs", "tutorials.delta", "Delta", Set.of("eng", "support"), FAKE,
                "Power cables connect the rack to the wall.");
        var template = new JdbcTemplate(dataSource);
        var tx = new DataSourceTransactionManager(dataSource);
        indexer = new EvalIndexer(template, tx, FAKE, EvalIndexer.FAKE, Set.of(EvalIndexer.FAKE, EvalIndexer.NOMIC),
                new CharRatioTokenEstimator());
        RagProperties rag = RagProperties.defaults();
        retriever = new HybridRetriever(new ChunkSearchRepository(jdbc, tx, rag), new EvalMain.NoQueryEmbedder(), rag,
                new SimpleMeterRegistry());
    }

    @AfterEach
    void cleanUp() {
        retriever.close();
        for (String t : List.of(source, target)) {
            RagFixtures.deleteTenantDocuments(jdbc, t);
            jdbc.sql("DELETE FROM tenants WHERE id = ?").param(t).update();
        }
    }

    @Test
    void indexesTheSourceIntoTheEvalTenantWithItsGroups() {
        var docs = indexer.readSource(source, "k8s-docs");
        assertThat(docs).extracting(EvalIndexer.SourceDocument::docId)
                .containsExactly("concepts.beta", "setup.alpha", "tutorials.delta", "tutorials.gamma");
        assertThat(EvalIndexer.allGroups(docs)).containsExactly("eng", "finance", "support");

        EvalIndexer.Report first = indexer.index(docs, target, EvalIndexer.ChunkSize.C200);
        assertThat(first.indexed()).isEqualTo(4);
        assertThat(first.chunks()).isPositive();
        assertThat(jdbc.sql("""
                        SELECT DISTINCT array_to_string(acl_groups, ',') FROM chunks
                         WHERE tenant_id = ? AND collection_id = 'k8s-c200' AND doc_id = 'setup.alpha'""")
                .param(target).query(String.class).list()).containsExactly("eng,finance");
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE tenant_id = ? AND text LIKE '%flux capacitor%'")
                .param(target).query(Integer.class).single()).isEqualTo(1);

        // a source document that disappears is deleted from the eval collection
        EvalIndexer.Report fewer = indexer.index(docs.subList(0, 3), target, EvalIndexer.ChunkSize.C200);
        assertThat(fewer.deleted()).isEqualTo(1);
        assertThat(fewer.unchanged()).isEqualTo(3);
    }

    @Test
    void scoresKeywordRetrievalOverAnswerableQuestionsOnly() {
        indexer.index(indexer.readSource(source, "k8s-docs"), target, EvalIndexer.ChunkSize.C200);
        var runner = new RetrievalEvalRunner(retriever, jdbc);
        RetrievalEvalRunner.Result r = runner.run(set, target, EvalIndexer.ChunkSize.C200, RetrievalMode.KEYWORD,
                Set.of("eng", "finance", "support"));
        // q01 and q02 find their passage first; q03 shares no word with the collection
        assertThat(r.summary().questions()).isEqualTo(3);
        assertThat(r.questions()).extracting(RetrievalEvalRunner.QuestionResult::firstRelevantRank)
                .containsExactly(1, 1, 0);
        assertThat(r.questions()).extracting(RetrievalEvalRunner.QuestionResult::totalRelevant)
                .containsExactly(1, 1, 1);
        assertThat(r.summary().recallAt1()).isEqualTo(0.666667);
        assertThat(r.summary().mrr()).isEqualTo(0.666667);
    }

    @Test
    void ac_t13_9_runningTwiceOnTheSameIndexGivesIdenticalMetrics() {
        var docs = indexer.readSource(source, "k8s-docs");
        indexer.index(docs, target, EvalIndexer.ChunkSize.C400);
        List<String> ids = chunkIds();
        var runner = new RetrievalEvalRunner(retriever, jdbc);
        var groups = Set.of("eng", "finance", "support");
        RetrievalEvalRunner.Result first = runner.run(set, target, EvalIndexer.ChunkSize.C400, RetrievalMode.KEYWORD, groups);

        // a second run reuses the index: nothing is re-chunked, the chunk ids (and so tie order) stay
        EvalIndexer.Report again = indexer.index(docs, target, EvalIndexer.ChunkSize.C400);
        assertThat(again.indexed()).isZero();
        assertThat(again.unchanged()).isEqualTo(4);
        assertThat(chunkIds()).isEqualTo(ids);
        RetrievalEvalRunner.Result second = runner.run(set, target, EvalIndexer.ChunkSize.C400, RetrievalMode.KEYWORD, groups);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void aKeywordRunReusesAnIndexBuiltWithTheRealModelAndARealModelRunReembedsAFakeOne() {
        var docs = indexer.readSource(source, "k8s-docs");
        var template = new JdbcTemplate(dataSource);
        var tx = new DataSourceTransactionManager(dataSource);
        // Index-version logic only: the fake stands in for the model's vectors here; no number comes from this.
        var modelRun = new EvalIndexer(template, tx, FAKE, EvalIndexer.NOMIC, Set.of(EvalIndexer.NOMIC),
                new CharRatioTokenEstimator());
        indexer.index(docs, target, EvalIndexer.ChunkSize.C200);
        assertThat(modelRun.index(docs, target, EvalIndexer.ChunkSize.C200).indexed()).isEqualTo(4);
        assertThat(indexer.index(docs, target, EvalIndexer.ChunkSize.C200).unchanged()).isEqualTo(4);
    }

    @Test
    void ac_t13_10_aclEvalReportsNoLeakAndTheAuthorisedRecall() {
        indexer.index(indexer.readSource(source, "k8s-docs"), target, EvalIndexer.ChunkSize.C400);
        AclEvalRunner.Result r = new AclEvalRunner(retriever, new AclAudit(jdbc))
                .run(set, target, EvalIndexer.ChunkSize.C400, RetrievalMode.KEYWORD);
        assertThat(r.questions()).isEqualTo(1);
        assertThat(r.unauthorisedLeakedChunks()).isZero();
        assertThat(r.leakedChunkIds()).isEmpty();
        // the support key still gets the tutorial about power cables: an answer from what it may see, not a leak
        assertThat(r.unauthorisedNoAnswerFraction()).isZero();
        assertThat(r.authorisedRecallAt5()).isEqualTo(1.0);
    }

    @Test
    void ac_t13_10_aclEvalCountsALeakWhenRetrievalShowsAForbiddenChunk() {
        indexer.index(indexer.readSource(source, "k8s-docs"), target, EvalIndexer.ChunkSize.C400);
        String forbidden = jdbc.sql("SELECT chunk_id::text FROM chunks WHERE tenant_id = ? AND doc_id = 'setup.alpha'"
                        + " AND collection_id = 'k8s-c400' ORDER BY ordinal LIMIT 1")
                .param(target).query(String.class).single();
        // a retriever whose ACL predicate is broken: it shows the setup chunk to anyone
        Retriever broken = q -> new RetrievalResult(List.of(new RetrievedChunk(forbidden, "setup.alpha", "Alpha", 0,
                ALPHA, 1.0, 1, 0)), q.mode());
        AclEvalRunner.Result r = new AclEvalRunner(broken, new AclAudit(jdbc))
                .run(set, target, EvalIndexer.ChunkSize.C400, RetrievalMode.KEYWORD);
        assertThat(r.unauthorisedLeakedChunks()).isEqualTo(1);
        assertThat(r.leakedChunkIds()).containsExactly(forbidden);
    }

    private List<String> chunkIds() {
        return jdbc.sql("SELECT chunk_id::text FROM chunks WHERE tenant_id = ? ORDER BY chunk_id")
                .param(target).query(String.class).list();
    }
}
