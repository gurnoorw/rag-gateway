package dev.raggateway.rag.ingest;

import dev.raggateway.support.OllamaEmbedJson;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Round-prep contracts for t09: the document hash, PUT body validation and the embed fixture. */
class IngestContractsTest {

    @Test
    void hashIs64HexAndIgnoresGroupOrder() {
        String a = DocumentHash.of("Pods", "A Pod is ...", List.of("eng", "sre"));
        assertThat(a).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(DocumentHash.of("Pods", "A Pod is ...", List.of("sre", "eng"))).isEqualTo(a);
    }

    @Test
    void hashChangesWithTitleContentOrGroups() {
        String base = DocumentHash.of("Pods", "text", Set.of("eng"));
        assertThat(DocumentHash.of("Pod", "text", Set.of("eng"))).isNotEqualTo(base);
        assertThat(DocumentHash.of("Pods", "text.", Set.of("eng"))).isNotEqualTo(base);
        assertThat(DocumentHash.of("Pods", "text", Set.of("eng", "sre"))).isNotEqualTo(base);
    }

    @Test
    void hashFieldsCannotRunTogether() {
        assertThat(DocumentHash.of("ab", "c", Set.of("g"))).isNotEqualTo(DocumentHash.of("a", "bc", Set.of("g")));
    }

    @Test
    void validUpsertKeepsItsValues() {
        var d = new DocumentUpsert("acme", "k8s-docs", "concepts.workloads.controllers.job", "Jobs", "body",
                Set.of("eng"));
        assertThat(d.contentHash()).isEqualTo(DocumentHash.of("Jobs", "body", Set.of("eng")));
    }

    @Test
    void invalidUpsertsAreRejected() {
        assertThatThrownBy(() -> new DocumentUpsert("acme", "a/b", "d", "t", "c", Set.of("eng")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("collection");
        assertThatThrownBy(() -> new DocumentUpsert("acme", "c", "-d", "t", "c", Set.of("eng")))
                .hasMessageContaining("document id");
        assertThatThrownBy(() -> new DocumentUpsert("acme", "c", "d", " ", "c", Set.of("eng")))
                .hasMessageContaining("title");
        assertThatThrownBy(() -> new DocumentUpsert("acme", "c", "d", "t", "", Set.of("eng")))
                .hasMessageContaining("content");
        assertThatThrownBy(() -> new DocumentUpsert("acme", "c", "d", "t", "c", Set.of()))
                .hasMessageContaining("acl_groups");
        assertThatThrownBy(() -> new DocumentUpsert("acme", "c", "d", "t", "c", Set.of("Eng")))
                .hasMessageContaining("ACL group");
    }

    @Test
    void embedFixtureHasTheDocumentedShape() throws Exception {
        var body = new ObjectMapper().readTree(OllamaEmbedJson.response(OllamaEmbedJson.MODEL, 2, 768));
        assertThat(body.get("model").asText()).isEqualTo("nomic-embed-text");
        assertThat(body.get("embeddings")).hasSize(2);
        assertThat(body.get("embeddings").get(0)).hasSize(768);
        float[] v = OllamaEmbedJson.vector(768, 7);
        double norm = 0;
        for (float x : v) norm += x * x;
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-4));
    }

    @Test
    void chunkerFixtureIsOnTheClasspath() throws Exception {
        try (var in = getClass().getResourceAsStream("/rag/chunker-fixture.md")) {
            assertThat(in).isNotNull();
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(text).startsWith("# ").contains("\n## ").contains("```");
        }
    }
}
