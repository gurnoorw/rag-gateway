package dev.raggateway.rag.ingest;

import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.TestData;
import dev.raggateway.support.TestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The V4 schema as Postgres sees it (AC-t09-5, -9, -10). Indexes are read from {@code pg_indexes}, which is what
 * {@code \d chunks} prints; psql itself is not run here.
 */
class RagSchemaTest extends AbstractPostgresTest {

    final String tenant = "schema-" + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.sql("DELETE FROM documents WHERE tenant_id = :t").param("t", tenant).update();
        jdbc.sql("DELETE FROM tenants WHERE id = :t").param("t", tenant).update();
    }

    static String vector(int dims) {
        return IntStream.range(0, dims).mapToObj(i -> "0.01").collect(Collectors.joining(",", "[", "]"));
    }

    UUID document() {
        TestData.insertTenant(jdbc, tenant, 1000, BigDecimal.ONE, Set.of());
        return new JdbcDocumentStore(jdbc).upsert(new DocumentUpsert(tenant, "c", "d", "T", "text", Set.of("eng")));
    }

    void insertChunk(UUID documentId, int ordinal, String vector) {
        jdbc.sql("""
                        INSERT INTO chunks (document_id, tenant_id, collection_id, doc_id, title, ordinal, text,
                                            embedding, acl_groups)
                        VALUES (:doc, :tenant, 'c', 'd', 'Pods', :ordinal, 'chunk text', CAST(:v AS vector), '{eng}')
                        """)
                .param("doc", documentId).param("tenant", tenant).param("ordinal", ordinal).param("v", vector)
                .update();
    }

    @Test
    void ac_t09_5_embeddingIsVector768AndA1024VectorIsRejected() {
        String type = jdbc.sql("""
                        SELECT format_type(a.atttypid, a.atttypmod) FROM pg_attribute a
                        WHERE a.attrelid = 'chunks'::regclass AND a.attname = 'embedding'
                        """).query(String.class).single();
        assertThat(type).isEqualTo("vector(768)");

        UUID doc = document();
        insertChunk(doc, 0, vector(768)); // the right size goes in
        assertThatThrownBy(() -> insertChunk(doc, 1, vector(1024)))
                .hasMessageContaining("expected 768 dimensions, not 1024");
        assertThat(jdbc.sql("SELECT count(*) FROM chunks WHERE document_id = :d").param("d", doc).query(Integer.class)
                .single()).isEqualTo(1);
    }

    @Test
    void ac_t09_9_chunksHaveHnswCosineAndGinIndexes() {
        List<String> defs = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'chunks'")
                .query(String.class).list();
        assertThat(defs).anySatisfy(d -> assertThat(d).contains("USING hnsw (embedding vector_cosine_ops)"));
        assertThat(defs).anySatisfy(d -> assertThat(d).contains("USING gin (tsv)"));
        assertThat(defs).anySatisfy(d -> assertThat(d).contains("USING gin (acl_groups)"));
        assertThat(defs).anySatisfy(d -> assertThat(d).contains("(tenant_id, collection_id)"));
    }

    @Test
    void ac_t09_10_pgvectorIsAtLeast0_8_0AndComposePinsA0_8xImage() throws Exception {
        String version = jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
                .query(String.class).single();
        int[] parts = Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
        assertThat(parts[0] > 0 || parts[1] >= 8).as("pgvector %s >= 0.8.0", version).isTrue();

        // Gradle runs tests with the project directory as the working directory.
        String compose = Files.readString(Path.of("docker-compose.yml"));
        var pin = Pattern.compile("image:\\s*pgvector/pgvector:(0\\.8\\.\\d+)-pg16\\s");
        assertThat(pin.matcher(compose).find()).as("docker-compose.yml pins pgvector 0.8.x").isTrue();
        assertThat(TestImages.POSTGRES.getVersionPart()).matches("0\\.8\\.\\d+-pg16");
    }

    @Test
    void tsvIsGeneratedFromTitleAndText() {
        UUID doc = document();
        insertChunk(doc, 0, vector(768));
        Boolean matches = jdbc.sql("SELECT tsv @@ to_tsquery('english', 'chunk & pod') FROM chunks WHERE document_id = :d")
                .param("d", doc).query(Boolean.class).single();
        assertThat(matches).isTrue();
    }

    @Test
    void outboxHasTheV5IndexesForCascadesAndTheDeadGauge() {
        List<String> defs = jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'outbox'")
                .query(String.class).list();
        assertThat(defs).anySatisfy(d -> assertThat(d).contains("outbox_document").contains("(document_id, created_at)"));
        assertThat(defs).anySatisfy(d -> assertThat(d).contains("outbox_dead").contains("WHERE (status = 'DEAD'::text)"));
    }
}
