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

    /**
     * A unit vector near {@code centre}: centre plus Gaussian noise of {@code spread} per dimension, normalised.
     * For HNSW tests: the fake embedder's vectors are sparse, mostly orthogonal (exact distance ties) and collide, which
     * builds a poorly connected graph (the t10 under-fill flake, D-49 amendment); dense clustered vectors behave like a
     * real model's.
     */
    public static float[] near(float[] centre, double spread, long seed) {
        var random = new java.util.SplittableRandom(seed);
        float[] v = new float[centre.length];
        double norm = 0;
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (centre[i] + spread * random.nextGaussian());
            norm += (double) v[i] * v[i];
        }
        double scale = 1 / Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) v[i] = (float) (v[i] * scale);
        return v;
    }

    /** A random dense unit vector (a cluster centre). */
    public static float[] centre(long seed) {
        return near(new float[768], 1, seed);
    }

    /**
     * The D-49 HNSW shape in one collection: 1,500 chunks in group {@code eng} clustered near {@code question}'s
     * centre, and 60 (~4%) in group {@code rare} clustered elsewhere. Texts are {@code noise <i>} and {@code rare <i>}.
     *
     * @return the question vector (near the noise cluster)
     */
    public static float[] hnswUnderFillShape(JdbcClient jdbc, String tenant, String collection, Embedder embedder) {
        float[] noiseCentre = centre(1);
        float[] rareCentre = centre(2);
        UUID noise = document(jdbc, tenant, collection, "noise", "Noise", Set.of("eng"), embedder);
        for (int i = 0; i < 1_500; i++) {
            chunk(jdbc, noise, tenant, collection, "noise", "Noise", i, "noise " + i, new String[]{"eng"},
                    near(noiseCentre, 0.03, 100 + i));
        }
        UUID rare = document(jdbc, tenant, collection, "rare", "Rare", Set.of("rare"), embedder);
        for (int i = 0; i < 60; i++) {
            chunk(jdbc, rare, tenant, collection, "rare", "Rare", i, "rare " + i, new String[]{"rare"},
                    near(rareCentre, 0.03, 10_000 + i));
        }
        jdbc.sql("ANALYZE chunks").update();
        return near(noiseCentre, 0.03, 99);
    }
}
