package dev.raggateway.rag;

import java.util.List;

/**
 * Retrieval strategy (LLD). Hybrid = keyword leg + vector leg in parallel, merged with reciprocal rank
 * fusion (k = 60, 1-based ranks, ties by chunk id). ACL, tenant and collection predicates are in the SQL
 * of both legs; no filtering after retrieval (C17, t12).
 */
public interface Retriever {

    List<RetrievedChunk> retrieve(RetrievalQuery query);
}
