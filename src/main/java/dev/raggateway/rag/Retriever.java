package dev.raggateway.rag;

/**
 * Retrieval strategy (LLD). Hybrid = keyword leg + vector leg in parallel, merged with reciprocal rank fusion
 * (k = 60, 1-based ranks; ties by the better single rank, then chunk id). ACL, tenant and collection predicates are
 * in the SQL of both legs; no filtering after retrieval (C17, t12). Differs from the LLD's {@code List<Chunk>}:
 * returns a {@link RetrievalResult} so a hybrid query that lost its vector leg says so (D-49).
 */
public interface Retriever {

    RetrievalResult retrieve(RetrievalQuery query);
}
