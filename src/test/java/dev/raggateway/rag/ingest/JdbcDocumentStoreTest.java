package dev.raggateway.rag.ingest;

import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link JdbcDocumentStore} against real Postgres; rows scoped by this test's own tenants. */
class JdbcDocumentStoreTest extends AbstractPostgresTest {

    final JdbcDocumentStore store = new JdbcDocumentStore(jdbc);
    final String tenantA = "docs-a-" + UUID.randomUUID();
    final String tenantB = "docs-b-" + UUID.randomUUID();

    @BeforeEach
    void tenants() {
        TestData.insertTenant(jdbc, tenantA, 1000, BigDecimal.ONE, Set.of());
        TestData.insertTenant(jdbc, tenantB, 1000, BigDecimal.ONE, Set.of());
    }

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM documents WHERE tenant_id IN (:t)").param("t", java.util.List.of(tenantA, tenantB)).update();
        jdbc.sql("DELETE FROM tenants WHERE id IN (:t)").param("t", java.util.List.of(tenantA, tenantB)).update();
    }

    @Test
    void insertThenFindByAddressAndById() {
        var d = new DocumentUpsert(tenantA, "k8s", "concepts.pods", "Pods", "A Pod is ...", Set.of("sre", "eng"));
        UUID id = store.upsert(d);

        IndexedDocument found = store.find(tenantA, "k8s", "concepts.pods").orElseThrow();
        assertThat(found.id()).isEqualTo(id);
        assertThat(found.tenantId()).isEqualTo(tenantA);
        assertThat(found.collection()).isEqualTo("k8s");
        assertThat(found.docId()).isEqualTo("concepts.pods");
        assertThat(found.title()).isEqualTo("Pods");
        assertThat(found.content()).isEqualTo("A Pod is ...");
        assertThat(found.aclGroups()).containsExactlyInAnyOrder("eng", "sre");
        assertThat(found.contentHash()).isEqualTo(d.contentHash());
        assertThat(found.status()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(found.chunkCount()).isZero();
        assertThat(found.updatedAt()).isNotNull();
        assertThat(store.findById(id)).contains(found);
    }

    @Test
    void upsertKeepsIdAndChunkCountAndResetsStatusToQueued() {
        UUID id = store.upsert(new DocumentUpsert(tenantA, "k8s", "d1", "Old", "old", Set.of("eng")));
        jdbc.sql("UPDATE documents SET status = 'indexed', chunk_count = 7 WHERE id = :id").param("id", id).update();

        var v2 = new DocumentUpsert(tenantA, "k8s", "d1", "New", "new", Set.of("support"));
        assertThat(store.upsert(v2)).isEqualTo(id);

        IndexedDocument found = store.findById(id).orElseThrow();
        assertThat(found.title()).isEqualTo("New");
        assertThat(found.content()).isEqualTo("new");
        assertThat(found.aclGroups()).containsExactly("support");
        assertThat(found.contentHash()).isEqualTo(v2.contentHash());
        assertThat(found.status()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(found.chunkCount()).isEqualTo(7);
    }

    @Test
    void findIsScopedByTenant() {
        store.upsert(new DocumentUpsert(tenantA, "k8s", "same", "A's", "a", Set.of("eng")));
        assertThat(store.find(tenantB, "k8s", "same")).isEmpty();

        UUID b = store.upsert(new DocumentUpsert(tenantB, "k8s", "same", "B's", "b", Set.of("eng")));
        assertThat(store.find(tenantB, "k8s", "same").orElseThrow().id()).isEqualTo(b);
        assertThat(store.find(tenantA, "k8s", "same").orElseThrow().title()).isEqualTo("A's");
        assertThat(store.find(tenantA, "other", "same")).isEmpty();
    }

    @Test
    void markFailedSetsStatus() {
        UUID id = store.upsert(new DocumentUpsert(tenantA, "k8s", "d", "T", "c", Set.of("eng")));
        store.markFailed(id);
        assertThat(store.findById(id).orElseThrow().status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(store.findById(UUID.randomUUID())).isEmpty();
    }
}
