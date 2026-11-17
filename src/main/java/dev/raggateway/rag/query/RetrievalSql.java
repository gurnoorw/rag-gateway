package dev.raggateway.rag.query;

import dev.raggateway.rag.RagProperties;

import java.util.List;

/**
 * The two retrieval statements (t10 round prep, D-49), as named-parameter SQL for {@code JdbcClient}. Both carry
 * the tenant, collection and ACL predicates inside the statement (C16, C17), so a row the caller may not see never
 * leaves the database. Parameters: {@code :tenant}, {@code :collection}, {@code :groups} (a {@code String[]}),
 * {@code :limit}, and {@code :question} or {@code :vector} (a {@link dev.raggateway.rag.PgVector#literal}).
 */
public final class RetrievalSql {

    private RetrievalSql() {
    }

    /**
     * The keyword query (C19): the question's stemmed, stop-word-free lexemes from {@code plainto_tsquery}, joined
     * with OR instead of AND. The text form of a tsquery quotes and escapes each lexeme, so replacing {@code " & "}
     * with {@code " | "} and casting back with {@code ::tsquery} keeps every lexeme verbatim. Re-parsing it with
     * {@code to_tsquery} would not: that re-tokenises {@code 'dry-run'} into a phrase with the unstemmed
     * {@code 'dry'}, which never matches. A question of stop words only gives an empty tsquery: no rows, no error.
     * Operators typed by the caller ({@code & | ! <-> :*}) are not operators here: {@code plainto_tsquery} drops
     * them.
     */
    public static final String KEYWORD_TSQUERY =
            "replace(plainto_tsquery('english', :question)::text, ' & ', ' | ')::tsquery";

    /**
     * Keyword leg: Postgres full-text ranking by {@code ts_rank_cd} (cover density, C18; it has no IDF term),
     * best first, ties by chunk id so the order is stable (AC-t10-9). Uses the GIN index on {@code tsv}.
     */
    public static final String KEYWORD = """
            SELECT c.chunk_id, c.doc_id, c.title, c.ordinal, c.text,
                   ts_rank_cd(c.tsv, %1$s) AS rank
              FROM chunks c
             WHERE c.tenant_id = :tenant
               AND c.collection_id = :collection
               AND c.acl_groups && CAST(:groups AS text[])
               AND c.tsv @@ %1$s
             ORDER BY rank DESC, c.chunk_id
             LIMIT :limit
            """.formatted(KEYWORD_TSQUERY);

    /**
     * Vector leg: cosine distance to the question's vector ({@code Embedder.embedQuery}, which adds the
     * {@code search_query: } prefix). Ordered by the distance expression alone, so the HNSW index can serve it
     * (adding a tie-breaker column would turn the index scan into a sort over every matching row). Runs inside a
     * read-only transaction after {@link #vectorSessionSettings}.
     */
    public static final String VECTOR = """
            SELECT c.chunk_id, c.doc_id, c.title, c.ordinal, c.text,
                   c.embedding <=> CAST(:vector AS vector) AS distance
              FROM chunks c
             WHERE c.tenant_id = :tenant
               AND c.collection_id = :collection
               AND c.acl_groups && CAST(:groups AS text[])
             ORDER BY c.embedding <=> CAST(:vector AS vector)
             LIMIT :limit
            """;

    /**
     * Vector leg, exact: the same predicates and distance, ordered by {@code distance + 0} and then chunk id, which no
     * index can serve, so Postgres reads the rows the filter matches (through the tenant/collection btree or the ACL
     * GIN index) and sorts them. Run by the repository only when {@link #VECTOR} came back with fewer than
     * {@code :limit} rows (D-49 amendment): an HNSW scan is approximate even with an iterative scan, which stops at
     * {@code hnsw.max_scan_tuples} visited tuples or at {@code work_mem × hnsw.scan_mem_multiplier}, and cannot reach
     * nodes the graph does not connect. The fallback costs one sort over the matching rows, which is small exactly
     * when a selective filter caused the shortfall.
     */
    public static final String VECTOR_EXACT = """
            SELECT c.chunk_id, c.doc_id, c.title, c.ordinal, c.text,
                   c.embedding <=> CAST(:vector AS vector) AS distance
              FROM chunks c
             WHERE c.tenant_id = :tenant
               AND c.collection_id = :collection
               AND c.acl_groups && CAST(:groups AS text[])
             ORDER BY (c.embedding <=> CAST(:vector AS vector)) + 0, c.chunk_id
             LIMIT :limit
            """;

    /**
     * {@code SET LOCAL} statements to run first in the vector leg's transaction (D-49). pgvector applies the
     * filter after the HNSW scan, and its default {@code ef_search} of 40 caps a scan at 40 rows, so without these
     * the leg returns fewer than {@code candidates} rows, and with a selective tenant or ACL filter only a few
     * (measured in the t10 prep: 40 of 50 without a filter, 1 of 50 with a 4% filter; 50 with these settings).
     * They raise the odds of a full list, not a guarantee: {@link #VECTOR_EXACT} covers the rest. Values come from
     * typed, validated config, never from a request.
     */
    public static List<String> vectorSessionSettings(RagProperties.Retrieval r) {
        return List.of(
                "SET LOCAL hnsw.ef_search = " + r.efSearch(),
                "SET LOCAL hnsw.iterative_scan = " + r.iterativeScan().sqlValue(),
                "SET LOCAL hnsw.max_scan_tuples = " + r.maxScanTuples());
    }
}
