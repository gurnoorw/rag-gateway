package dev.raggateway.rag;

import dev.raggateway.rag.query.RetrievalSql;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code gateway.rag.retrieval} and {@code gateway.rag.answer} (t10 round prep, D-49). */
class RagRetrievalPropertiesTest {

    @Test
    void defaultsAreTheD49Values() {
        RagProperties rag = RagConfigTest.bind(Map.of());
        assertThat(rag.retrieval()).isEqualTo(new RagProperties.Retrieval(50, 60, 100,
                RagProperties.IterativeScan.STRICT_ORDER, 20_000));
        assertThat(rag.answer()).isEqualTo(new RagProperties.Answer(512, 2000, 5));
        assertThat(RetrievalSql.vectorSessionSettings(rag.retrieval())).containsExactly(
                "SET LOCAL hnsw.ef_search = 100",
                "SET LOCAL hnsw.iterative_scan = strict_order",
                "SET LOCAL hnsw.max_scan_tuples = 20000");
    }

    @Test
    void bindsTheDocumentedNames() {
        RagProperties rag = RagConfigTest.bind(Map.of(
                "gateway.rag.retrieval.candidates", "30",
                "gateway.rag.retrieval.rrf-k", "10",
                "gateway.rag.retrieval.ef-search", "64",
                "gateway.rag.retrieval.iterative-scan", "off",
                "gateway.rag.retrieval.max-scan-tuples", "5000",
                "gateway.rag.answer.max-tokens", "300",
                "gateway.rag.answer.max-question-chars", "500",
                "gateway.rag.answer.default-top-k", "8"));
        assertThat(rag.retrieval()).isEqualTo(new RagProperties.Retrieval(30, 10, 64,
                RagProperties.IterativeScan.OFF, 5000));
        assertThat(rag.answer()).isEqualTo(new RagProperties.Answer(300, 500, 8));
        assertThat(RagProperties.IterativeScan.OFF.sqlValue()).isEqualTo("off");
        assertThat(RagProperties.IterativeScan.RELAXED_ORDER.sqlValue()).isEqualTo("relaxed_order");
    }

    @Test
    void efSearchBelowTheCandidateCountIsRejected() {
        // pgvector's default ef_search (40) below 50 candidates is the under-fill D-49 exists to prevent.
        assertThatThrownBy(() -> new RagProperties.Retrieval(50, 60, 40, RagProperties.IterativeScan.STRICT_ORDER, 20_000))
                .hasMessageContaining("ef-search");
    }
}
