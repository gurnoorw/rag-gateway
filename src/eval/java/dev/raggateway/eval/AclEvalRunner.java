package dev.raggateway.eval;

import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.Retriever;
import dev.raggateway.rag.query.AclAudit;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The ACL eval (t13, DECISIONS.md D-51, D-53): every {@code acl} question is asked twice through the production
 * {@link Retriever} with the RAG endpoint's default top {@value #TOP_K}.
 * <ul>
 *   <li><b>Unauthorised</b>, by a key holding only {@link #UNAUTHORISED_GROUPS} ({@code support}; the gold documents
 *       are in {@code setup} or {@code concepts}, which that group cannot see). Every chunk shown is audited with
 *       {@link AclAudit}, which re-derives visibility from the chunk rows instead of reusing the retrieval
 *       predicates; the leaked count must be 0. The no-answer share is the share of questions where retrieval
 *       returned nothing, which is when {@code POST /v1/rag/query} answers {@code no_answer: true} without a model
 *       call. Chunks of documents the key may see (tutorials) are not leaks, so with OR-semantics keyword search
 *       this share is expected to be low; whether the model then refuses is the answer eval's question.</li>
 *   <li><b>Authorised</b>, by a key holding the question's {@code required_groups}: recall@5, the share of
 *       questions with a relevant chunk ({@link EvidenceMatcher}) in the top {@value #TOP_K}.</li>
 * </ul>
 */
public final class AclEvalRunner {

    public static final int TOP_K = 5;
    public static final Set<String> UNAUTHORISED_GROUPS = Set.of("support");

    /**
     * @param leakedChunkIds every leaked id, with duplicates across questions (empty when the ACL holds)
     */
    public record Result(RetrievalMode mode, EvalIndexer.ChunkSize size, int questions, int unauthorisedLeakedChunks,
                         double unauthorisedNoAnswerFraction, double authorisedRecallAt5,
                         List<String> leakedChunkIds) {
        public Result {
            leakedChunkIds = List.copyOf(leakedChunkIds);
        }
    }

    private final Retriever retriever;
    private final AclAudit audit;

    public AclEvalRunner(Retriever retriever, AclAudit audit) {
        this.retriever = Objects.requireNonNull(retriever, "retriever");
        this.audit = Objects.requireNonNull(audit, "audit");
    }

    public Result run(EvalSet set, String tenant, EvalIndexer.ChunkSize size, RetrievalMode mode) {
        List<EvalSet.Question> questions = set.ofKind(EvalSet.Kind.ACL);
        if (questions.isEmpty()) throw new IllegalArgumentException("the eval set has no acl questions");
        String collection = size.collection();
        List<String> leaked = new ArrayList<>();
        int noAnswer = 0, hits = 0;
        for (EvalSet.Question q : questions) {
            List<RetrievedChunk> shown = retriever.retrieve(new RetrievalQuery(tenant, collection, q.question(),
                    UNAUTHORISED_GROUPS, TOP_K, mode)).chunks();
            if (shown.isEmpty()) noAnswer++;
            leaked.addAll(audit.leakedChunkIds(tenant, collection, UNAUTHORISED_GROUPS,
                    shown.stream().map(RetrievedChunk::chunkId).toList()));

            List<RetrievedChunk> authorised = retriever.retrieve(new RetrievalQuery(tenant, collection, q.question(),
                    q.requiredGroups(), TOP_K, mode)).chunks();
            if (authorised.stream().anyMatch(c -> EvidenceMatcher.relevant(c.docId(), c.text(), q.gold()))) hits++;
        }
        int n = questions.size();
        return new Result(mode, size, n, leaked.size(), RetrievalMetrics.round((double) noAnswer / n),
                RetrievalMetrics.round((double) hits / n), leaked);
    }
}
