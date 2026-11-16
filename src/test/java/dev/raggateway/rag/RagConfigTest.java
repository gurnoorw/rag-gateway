package dev.raggateway.rag;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ProviderProperties;
import dev.raggateway.rag.ingest.FakeEmbedder;
import dev.raggateway.rag.ingest.OllamaEmbedder;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@code gateway.rag} binding (D-47 names and defaults) and the embedder choice (D-18). */
class RagConfigTest {

    static RagProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("gateway.rag", RagProperties.class);
    }

    @Test
    void defaultsAreTheD47Values() {
        RagProperties rag = bind(Map.of());
        assertThat(rag).isEqualTo(RagProperties.defaults());
        assertThat(rag.embedder()).isEqualTo(RagProperties.EmbedderKind.OLLAMA);
        assertThat(rag.embedModel()).isEqualTo("nomic-embed-text");
        assertThat(rag.embedDimensions()).isEqualTo(768);
        assertThat(rag.embedBatchSize()).isEqualTo(20);
        assertThat(rag.embedTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(rag.maxDocumentBytes()).isEqualTo(DataSize.ofMegabytes(1));
        assertThat(rag.maxConcurrentWrites()).isEqualTo(4);
        assertThat(rag.worker()).isEqualTo(new RagProperties.Worker(true, Duration.ofSeconds(1), 4, Duration.ofMinutes(5)));
        assertThat(rag.chunk()).isEqualTo(new RagProperties.Chunking(400, 50, 400));
    }

    @Test
    void bindsTheDocumentedNames() {
        RagProperties rag = bind(Map.of(
                "gateway.rag.embedder", "fake",
                "gateway.rag.embed-batch-size", "8",
                "gateway.rag.max-document-bytes", "2MB",
                "gateway.rag.max-concurrent-writes", "1",
                "gateway.rag.chunk.target-tokens", "200",
                "gateway.rag.chunk.overlap-tokens", "25",
                "gateway.rag.chunk.max-tokens", "200",
                "gateway.rag.worker.enabled", "false",
                "gateway.rag.worker.lease", "30s"));
        assertThat(rag.embedder()).isEqualTo(RagProperties.EmbedderKind.FAKE);
        assertThat(rag.embedBatchSize()).isEqualTo(8);
        assertThat(rag.maxDocumentBytes().toBytes()).isEqualTo(2L * 1024 * 1024);
        assertThat(rag.maxConcurrentWrites()).isEqualTo(1);
        assertThat(rag.chunk()).isEqualTo(new RagProperties.Chunking(200, 25, 200));
        assertThat(rag.worker().enabled()).isFalse();
        assertThat(rag.worker().lease()).isEqualTo(Duration.ofSeconds(30));
        assertThat(rag.worker().claimBatch()).isEqualTo(4);
    }

    @Test
    void rejectsAnOverlapNotBelowTheTarget() {
        assertThatThrownBy(() -> new RagProperties.Chunking(100, 100, 100)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void embedderChoice() {
        var gateway = new GatewayProperties(1024, 8192, Map.of(), Map.of("ollama",
                new ProviderProperties("http://127.0.0.1:11434", null, Duration.ofSeconds(1), null, null)));
        var config = new RagConfig();

        assertThat(config.embedder(bind(Map.of("gateway.rag.embedder", "fake")), gateway)).isInstanceOf(FakeEmbedder.class);
        Embedder ollama = config.embedder(bind(Map.of()), gateway);
        assertThat(ollama).isInstanceOf(OllamaEmbedder.class);
        assertThat(ollama.dimensions()).isEqualTo(768);
        ((OllamaEmbedder) ollama).close();

        var noOllama = new GatewayProperties(1024, 8192, Map.of(), Map.of());
        assertThatThrownBy(() -> config.embedder(bind(Map.of()), noOllama))
                .hasMessageContaining("gateway.providers.ollama.base-url");
    }
}
