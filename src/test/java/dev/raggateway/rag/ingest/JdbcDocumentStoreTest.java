package dev.raggateway.rag.ingest;

import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.raggateway.rag.ChunkText;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** Locks the tenant's document 'd' on a fresh connection with NOWAIT. */
    static boolean lockNowait(String tenant) throws java.sql.SQLException {
        try (var c = dataSource.getConnection();
             var ps = c.prepareStatement("SELECT id FROM documents WHERE tenant_id = ? AND doc_id = 'd' FOR UPDATE NOWAIT")) {
            ps.setString(1, tenant);
            try (var rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @Test
    void lockForUpdateHoldsTheRowUntilTheTransactionEnds() throws Exception {
        store.upsert(new DocumentUpsert(tenantA, "k8s", "d", "T", "c", Set.of("eng")));
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        tx.executeWithoutResult(s -> {
            assertThat(store.lockForUpdate(tenantA, "k8s", "d")).isPresent();
            // A second connection (raw, outside the transaction) cannot lock the row now: NOWAIT fails.
            assertThatThrownBy(() -> lockNowait(tenantA)).hasMessageContaining("could not obtain lock");
        });
        assertThat(lockNowait(tenantA)).isTrue();   // released at commit
        assertThat(store.lockForUpdate(tenantB, "k8s", "d")).isEmpty();
    }

    @Test
    void updateAclSetsTheDocumentsGroupsAndHashAndEveryChunksGroupsOnly() {
        UUID id = store.upsert(new DocumentUpsert(tenantA, "k8s", "d", "T", "c", Set.of("eng")));
        IndexedDocument doc = store.findById(id).orElseThrow();
        var chunks = new JdbcChunkStore(new JdbcTemplate(dataSource), new DataSourceTransactionManager(dataSource));
        var fake = new FakeEmbedder();
        List<ChunkText> pieces = List.of(new ChunkText(0, "one", 1), new ChunkText(1, "two", 1));
        assertThat(chunks.replaceForDocument(doc, pieces, fake.embedDocuments(List.of("one", "two")))).isTrue();
        String newHash = DocumentHash.of("T", "c", Set.of("sre", "eng"));

        assertThat(store.updateAcl(id, Set.of("sre", "eng"), newHash)).isEqualTo(2);

        IndexedDocument after = store.findById(id).orElseThrow();
        assertThat(after.aclGroups()).containsExactlyInAnyOrder("eng", "sre");
        assertThat(after.contentHash()).isEqualTo(newHash);
        assertThat(after.status()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(after.chunkCount()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT array_to_string(acl_groups, ',') FROM chunks WHERE document_id = :id")
                .param("id", id).query(String.class).list()).containsExactly("eng,sre", "eng,sre");
    }

    @Test
    void deleteIsScopedByTenantAndCascadesToChunksAndJobs() {
        UUID a = store.upsert(new DocumentUpsert(tenantA, "k8s", "same", "T", "c", Set.of("eng")));
        UUID b = store.upsert(new DocumentUpsert(tenantB, "k8s", "same", "T", "c", Set.of("eng")));
        new JdbcOutboxStore(jdbc, new DataSourceTransactionManager(dataSource)).insert(a, "h".repeat(64));

        assertThat(store.delete(tenantB, "k8s", "same")).isTrue();
        assertThat(store.delete(tenantB, "k8s", "same")).isFalse();
        assertThat(store.findById(b)).isEmpty();
        assertThat(store.findById(a)).isPresent();   // another tenant's document at the same address is untouched

        assertThat(store.delete(tenantA, "k8s", "same")).isTrue();
        assertThat(store.findById(a)).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE document_id = :id").param("id", a).query(Integer.class)
                .single()).isZero();
    }
}
