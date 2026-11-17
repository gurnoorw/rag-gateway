package dev.raggateway.support;

import dev.raggateway.rag.Embedder;
import dev.raggateway.rag.PgVector;
import dev.raggateway.rag.ingest.DocumentHash;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Set;
import java.util.UUID;

/**
 * Inserts documents and chunks directly, without the worker (t10 round prep), for retrieval and RAG tests.
 * Chunk vectors come from the given embedder over {@code <title>\n\n<text>}, as the indexing worker embeds them
 * (D-47), so a test's {@code embedQuery} lands near its own chunks with the fake embedder. Every row carries the
 * test's own tenant: delete by tenant afterwards ({@link #deleteTenantDocuments}); chunks cascade.
 *
 * <pre>{@code
 * UUID doc = RagFixtures.document(jdbc, tenant, "k8s", "concepts.jobs", "Jobs", Set.of("eng"),
 *         embedder, "A Job runs Pods until a number of them succeed.", "Use backoffLimit to cap retries.");
 * }</pre>
 */
public final class RagFixtures {

    private RagFixtures() {
    }

    /** One document with its chunks (ordinals 0..n-1), status {@code indexed}. Returns the document's id. */
    public static UUID document(JdbcClient jdbc, String tenant, String collection, String docId, String title,
                                Set<String> groups, Embedder embedder, String... chunkTexts) {
        String content = String.join("\n\n", chunkTexts);
        String[] acl = groups.stream().sorted().toArray(String[]::new);
        UUID id = jdbc.sql("""
                        INSERT INTO documents (tenant_id, collection_id, doc_id, title, content, acl_groups,
                                               content_hash, status, chunk_count)
                        VALUES (:tenant, :collection, :docId, :title, :content, CAST(:groups AS text[]), :hash,
                                'indexed', :count)
                        RETURNING id""")
                .param("tenant", tenant).param("collection", collection).param("docId", docId)
                .param("title", title).param("content", content).param("groups", acl)
                .param("hash", DocumentHash.of(title, content, groups)).param("count", chunkTexts.length)
                .query(UUID.class).single();
        for (int i = 0; i < chunkTexts.length; i++) {
            float[] vector = embedder.embedDocuments(java.util.List.of(title + "\n\n" + chunkTexts[i])).getFirst();
            chunk(jdbc, id, tenant, collection, docId, title, i, chunkTexts[i], acl, vector);
        }
        return id;
    }

    /** One chunk row with an explicit vector. */
    public static UUID chunk(JdbcClient jdbc, UUID documentId, String tenant, String collection, String docId,
                             String title, int ordinal, String text, String[] groups, float[] vector) {
        return jdbc.sql("""
                        INSERT INTO chunks (document_id, tenant_id, collection_id, doc_id, title, ordinal, text,
                                            embedding, acl_groups)
                        VALUES (:doc, :tenant, :collection, :docId, :title, :ordinal, :text, CAST(:vector AS vector),
                                CAST(:groups AS text[]))
                        RETURNING chunk_id""")
                .param("doc", documentId).param("tenant", tenant).param("collection", collection)
                .param("docId", docId).param("title", title).param("ordinal", ordinal).param("text", text)
                .param("vector", PgVector.literal(vector)).param("groups", groups)
                .query(UUID.class).single();
    }

    /** Removes the tenant's documents (chunks and jobs cascade). The tenant row itself is the caller's. */
    public static void deleteTenantDocuments(JdbcClient jdbc, String tenant) {
        jdbc.sql("DELETE FROM documents WHERE tenant_id = :tenant").param("tenant", tenant).update();
    }
}
