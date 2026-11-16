package dev.raggateway.rag;

import java.util.Set;

/**
 * Differs from the LLD {@code retrieve(collection, question, groups, topK)}: adds the tenant (C17) and the
 * mode (t10), as one record so the signature stays stable (D-14).
 *
 * @param tenantId   caller's tenant; every SQL leg filters by it
 * @param collection collection id
 * @param question   user question
 * @param groups     caller's ACL groups; empty returns nothing (AC-t12-4)
 * @param topK       1..20, default 5
 * @param mode       legs to run
 */
public record RetrievalQuery(String tenantId, String collection, String question, Set<String> groups,
                             int topK, RetrievalMode mode) {
    public RetrievalQuery {
        groups = Set.copyOf(groups);
        if (topK < 1 || topK > 20) throw new IllegalArgumentException("topK must be 1..20");
    }
}
