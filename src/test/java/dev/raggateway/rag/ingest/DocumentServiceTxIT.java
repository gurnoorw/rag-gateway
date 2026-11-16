package dev.raggateway.rag.ingest;

import dev.raggateway.rag.ingest.ControllableOutboxConfig.ControllableOutboxStore;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC-t09-3: the document row and the outbox row are written atomically by the real, Spring-proxied
 * {@link DocumentService}. A failure forced after the document write (before or after the outbox insert) leaves
 * neither row; on an update it leaves the previous version and its one job untouched.
 */
@Import(ControllableOutboxConfig.class)
@TestPropertySource(properties = "gateway.rag.max-concurrent-writes=1") // same context as DocumentControllerIT
class DocumentServiceTxIT extends AbstractIntegrationTest {

    static final String TENANT = "tx-tenant";

    @Autowired
    DocumentService service;

    @Autowired
    ControllableOutboxStore outbox;

    @BeforeEach
    void seed() {
        outbox.reset();
        TestData.insertTenant(jdbc, TENANT, 1000, BigDecimal.ONE, Set.of());
    }

    @AfterEach
    void disarm() {
        outbox.reset();
    }

    DocumentUpsert doc(String title) {
        return new DocumentUpsert(TENANT, "k8s", "concepts.pods", title, "A Pod is the smallest unit.", Set.of("eng"));
    }

    int documents() {
        return jdbc.sql("SELECT count(*) FROM documents WHERE tenant_id = :t").param("t", TENANT).query(Integer.class).single();
    }

    int jobs() {
        return jdbc.sql("""
                        SELECT count(*) FROM outbox o JOIN documents d ON d.id = o.document_id WHERE d.tenant_id = :t""")
                .param("t", TENANT).query(Integer.class).single();
    }

    @Test
    void theServiceIsATransactionalProxy() {
        assertThat(AopUtils.isAopProxy(service)).isTrue();
    }

    @Test
    void upsertWritesTheDocumentAndOneJobWithItsHash() {
        UpsertResult result = service.upsert(doc("Pods"));

        assertThat(result.queued()).isTrue();
        assertThat(documents()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT content_hash FROM outbox WHERE document_id = :d AND status = 'PENDING'")
                .param("d", result.documentId()).query(String.class).single()).isEqualTo(doc("Pods").contentHash());
    }

    @Test
    void ac_t09_3_failureAfterBothInsertsLeavesNeitherRow() {
        outbox.failNextInsertAfterWriting(new IllegalStateException("forced after the outbox insert"));

        assertThatThrownBy(() -> service.upsert(doc("Pods"))).hasMessageContaining("forced");

        assertThat(documents()).isZero();
        assertThat(jobs()).isZero();
    }

    @Test
    void ac_t09_3_failureBetweenTheDocumentWriteAndTheOutboxInsertLeavesNeitherRow() {
        outbox.failNextInsertBeforeWriting(new IllegalStateException("forced after the document write"));

        assertThatThrownBy(() -> service.upsert(doc("Pods"))).hasMessageContaining("forced");

        assertThat(documents()).isZero();
        assertThat(jobs()).isZero();
    }

    @Test
    void ac_t09_3_failedUpdateKeepsThePreviousVersionAndItsSingleJob() {
        UUID id = service.upsert(doc("Pods")).documentId();
        jdbc.sql("UPDATE documents SET status = 'indexed', chunk_count = 3 WHERE id = :id").param("id", id).update();
        outbox.failNextInsertAfterWriting(new IllegalStateException("forced"));

        assertThatThrownBy(() -> service.upsert(doc("Pods, renamed"))).hasMessageContaining("forced");

        IndexedDocument kept = service.find(TENANT, "k8s", "concepts.pods").orElseThrow();
        assertThat(kept.id()).isEqualTo(id);
        assertThat(kept.title()).isEqualTo("Pods");
        assertThat(kept.contentHash()).isEqualTo(doc("Pods").contentHash());
        assertThat(kept.status()).isEqualTo(DocumentStatus.INDEXED);
        assertThat(kept.chunkCount()).isEqualTo(3);
        assertThat(jobs()).isEqualTo(1);
    }
}
