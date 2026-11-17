package dev.raggateway.rag.query;

import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.RagFixtures;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link AclAudit} re-derives visibility from the chunk rows (t12 round prep, D-51). */
class AclAuditTest extends AbstractPostgresTest {

    static final FakeEmbedder EMBEDDER = new FakeEmbedder();
    final String tenant = "t-audit-" + UUID.randomUUID();
    final String other = "t-audit-other-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        for (String t : List.of(tenant, other)) {
            RagFixtures.deleteTenantDocuments(jdbc, t);
            jdbc.sql("DELETE FROM tenants WHERE id = ?").param(t).update();
        }
    }

    String onlyChunk(String t, String collection, String docId, Set<String> groups) {
        UUID doc = RagFixtures.document(jdbc, t, collection, docId, "T", groups, EMBEDDER, "text of " + docId);
        return jdbc.sql("SELECT chunk_id::text FROM chunks WHERE document_id = ?").param(doc).query(String.class).single();
    }

    @Test
    void flagsEveryChunkTheCallerMayNotSee() {
        TestData.insertTenant(jdbc, tenant, 1000, BigDecimal.TEN, Set.of());
        TestData.insertTenant(jdbc, other, 1000, BigDecimal.TEN, Set.of());
        String visible = onlyChunk(tenant, "k8s", "eng.doc", Set.of("eng", "sre"));
        String otherGroup = onlyChunk(tenant, "k8s", "security.doc", Set.of("security"));
        String otherCollection = onlyChunk(tenant, "hr", "eng.doc", Set.of("eng"));
        String otherTenant = onlyChunk(other, "k8s", "eng.doc", Set.of("eng"));
        String missing = UUID.randomUUID().toString();
        var audit = new AclAudit(jdbc);

        assertThat(audit.leakedChunkIds(tenant, "k8s", Set.of("eng"), List.of(visible))).isEmpty();
        assertThat(audit.leakedChunkIds(tenant, "k8s", Set.of("eng"),
                List.of(visible, otherGroup, otherCollection, otherTenant, missing, otherGroup)))
                .containsExactly(otherGroup, otherCollection, otherTenant, missing);
        assertThat(audit.leakedChunkIds(tenant, "k8s", Set.of(), List.of(visible))).containsExactly(visible);
        assertThat(audit.leakedChunkIds(tenant, "k8s", Set.of("eng"), List.of())).isEmpty();
    }
}
