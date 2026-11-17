package dev.raggateway.eval;

import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.Retriever;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Scores one mode at one chunk size over the answerable questions (t13, DECISIONS.md D-53): each question goes
 * through the production {@link Retriever} with the top {@value RetrievalMetrics#DEPTH}, each returned chunk is judged
 * by {@link EvidenceMatcher}, and the collection's relevant chunks are counted for nDCG's ideal ranking.
 * Unanswerable and {@code acl} questions are not scored here.
 *
 * <p>The asker holds every group in the collection: these questions measure ranking, and ACL is measured by
 * {@link AclEvalRunner}. Deterministic on a fixed index: both legs order ties by chunk id (D-49), so a second run
 * gives the same rankings (AC-t13-9).
 */
public final class RetrievalEvalRunner {

    /** One question's outcome, for the run's log. */
    public record QuestionResult(String questionId, int firstRelevantRank, int totalRelevant) {
    }

    /** The metrics plus the per-question outcomes they came from. */
    public record Result(RetrievalMode mode, EvalIndexer.ChunkSize size, RetrievalMetrics.Summary summary,
                         List<QuestionResult> questions) {
    }

    private final Retriever retriever;
    private final JdbcClient jdbc;

    public RetrievalEvalRunner(Retriever retriever, JdbcClient jdbc) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Result run(EvalSet set, String tenant, EvalIndexer.ChunkSize size, RetrievalMode mode,
                      Set<String> groups) {
        List<RetrievalMetrics.Ranking> rankings = new ArrayList<>();
        List<QuestionResult> outcomes = new ArrayList<>();
        for (EvalSet.Question q : set.answerable()) {
            List<RetrievedChunk> top = retriever.retrieve(new RetrievalQuery(tenant, size.collection(), q.question(),
                    groups, RetrievalMetrics.DEPTH, mode)).chunks();
            List<Boolean> relevant = top.stream()
                    .map(c -> EvidenceMatcher.relevant(c.docId(), c.text(), q.gold())).toList();
            int total = relevantInCollection(tenant, size.collection(), q);
            var ranking = new RetrievalMetrics.Ranking(q.id(), relevant, total);
            rankings.add(ranking);
            outcomes.add(new QuestionResult(q.id(), ranking.firstRelevantRank(), total));
        }
        return new Result(mode, size, RetrievalMetrics.of(rankings), outcomes);
    }

    /** Chunks of the collection that are relevant to the question (gold documents only). */
    int relevantInCollection(String tenant, String collection, EvalSet.Question q) {
        record Chunk(String docId, String text) {
        }
        List<Chunk> candidates = jdbc.sql("""
                        SELECT doc_id, text FROM chunks
                         WHERE tenant_id = :tenant AND collection_id = :collection
                           AND doc_id = ANY(CAST(:docs AS text[]))""")
                .param("tenant", tenant).param("collection", collection)
                .param("docs", q.goldDocIds().toArray(String[]::new))
                .query((rs, n) -> new Chunk(rs.getString(1), rs.getString(2))).list();
        return (int) candidates.stream().filter(c -> EvidenceMatcher.relevant(c.docId(), c.text(), q.gold())).count();
    }
}
