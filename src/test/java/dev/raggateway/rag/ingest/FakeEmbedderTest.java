package dev.raggateway.rag.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The word-hashing fake embedder (D-18, D-47, AC-t09-16). */
class FakeEmbedderTest {

    final FakeEmbedder embedder = new FakeEmbedder();

    @Test
    void ac_t09_16_deterministicAcrossInstances() {
        String text = "A batch task runs until it finishes.";
        assertThat(new FakeEmbedder().embedQuery(text)).containsExactly(embedder.embedQuery(text));
        assertThat(embedder.embedDocuments(List.of(text)).getFirst()).containsExactly(embedder.embedQuery(text));
    }

    @Test
    void ac_t09_16_unitLengthWith768Values() {
        for (String text : List.of("one", "Two words", "A much longer text with many many repeated repeated words.", "")) {
            float[] v = embedder.embedQuery(text);
            assertThat(v).hasSize(768);
            assertThat(norm(v)).isCloseTo(1.0, within(1e-5));
        }
        assertThat(embedder.dimensions()).isEqualTo(768);
    }

    @Test
    void ac_t09_16_ignoresTheTaskPrefix() {
        String text = "How do I set a retry limit?";
        float[] plain = embedder.vector(text);
        assertThat(embedder.vector(FakeEmbedder.QUERY_PREFIX + text)).containsExactly(plain);
        assertThat(embedder.vector(FakeEmbedder.DOCUMENT_PREFIX + text)).containsExactly(plain);
    }

    @Test
    void ac_t09_16_sharedWordsScoreHigherThanUnrelatedTexts() {
        float[] a = embedder.embedQuery("The controller retries a failed batch task up to the retry limit.");
        float[] b = embedder.embedQuery("A failed batch task is retried by the controller until the retry limit.");
        float[] c = embedder.embedQuery("Grafana dashboards show request latency percentiles per tenant.");
        assertThat(cosine(a, b)).isGreaterThan(cosine(a, c) + 0.3);
    }

    @Test
    void caseAndPunctuationDoNotMatter() {
        assertThat(embedder.embedQuery("Retry, limit!")).containsExactly(embedder.embedQuery("retry limit"));
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return dot / (norm(a) * norm(b));
    }

    static double norm(float[] v) {
        double s = 0;
        for (float x : v) s += (double) x * x;
        return Math.sqrt(s);
    }
}
