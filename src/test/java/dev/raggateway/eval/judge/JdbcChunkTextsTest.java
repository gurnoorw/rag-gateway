package dev.raggateway.eval.judge;

import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.SharedContainers;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The collect step reads source text by chunk id, scoped to the eval tenant and collection. */
class JdbcChunkTextsTest extends AbstractPostgresTest {

    static final String TENANT = "t13-judge-texts";
    static final String OTHER = "t13-judge-texts-other";

    @BeforeEach
    void tenants() {
        for (String t : List.of(TENANT, OTHER)) TestData.insertTenant(jdbc, t, 1000, BigDecimal.TEN, Set.of());
    }

    @AfterEach
    void clean() {
        for (String t : List.of(TENANT, OTHER)) {
            RagFixtures.deleteTenantDocuments(jdbc, t);
            jdbc.sql("DELETE FROM tenants WHERE id = :id").param("id", t).update();
        }
    }

    String chunkId(String tenant, String collection, String text) {
        UUID doc = RagFixtures.document(jdbc, tenant, collection, "doc-" + UUID.randomUUID(), "Title", Set.of("eng"),
                new FakeEmbedder(), text);
        return jdbc.sql("SELECT chunk_id::text FROM chunks WHERE document_id = :d").param("d", doc)
                .query(String.class).single();
    }

    @Test
    void readsTextOfTheRequestedChunksInTheEvalCollectionOnly() {
        String a = chunkId(TENANT, "k8s-c400", "first chunk text");
        String b = chunkId(TENANT, "k8s-c400", "second chunk text");
        String otherCollection = chunkId(TENANT, "k8s-c200", "other collection text");
        String otherTenant = chunkId(OTHER, "k8s-c400", "other tenant text");
        var pg = SharedContainers.postgres();
        var texts = new AnswerEvalRunner.JdbcChunkTexts(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword(), TENANT,
                "k8s-c400");

        Map<String, String> found = texts.texts(List.of(a, b, otherCollection, otherTenant));

        assertThat(found).containsOnly(Map.entry(a, "first chunk text"), Map.entry(b, "second chunk text"));
    }
}
