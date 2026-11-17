package dev.raggateway.rag.query;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.config.GatewayProperties.ProviderProperties;
import dev.raggateway.provider.ollama.OllamaClientConfig;
import dev.raggateway.rag.RagProperties;
import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.ingest.OllamaEmbedder;
import dev.raggateway.support.OllamaEmbedJson;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-t10-11: the question a retrieval embeds reaches Ollama's {@code /api/embed} with the {@code search_query: }
 * prefix (D-47, D-48), added once, by the adapter, and never as a document. WireMock stands in for Ollama, which is
 * not reachable here; the request shape is the one {@link OllamaEmbedJson} documents.
 */
class OllamaEmbedderQueryPrefixTest {

    static WireMockServer ollama;
    OllamaEmbedder embedder;
    HybridRetriever retriever;

    @BeforeAll
    static void start() {
        ollama = new WireMockServer(wireMockConfig().dynamicPort());
        ollama.start();
    }

    @AfterAll
    static void stop() {
        ollama.stop();
    }

    @BeforeEach
    void setUp() {
        ollama.resetAll();
        ollama.stubFor(post("/api/embed").willReturn(okJson(OllamaEmbedJson.response(OllamaEmbedJson.MODEL, 1, 768))));
        var props = new ProviderProperties(ollama.baseUrl(), null, Duration.ofSeconds(1), null, null);
        embedder = new OllamaEmbedder(OllamaClientConfig.client(props), ollama.baseUrl(), OllamaEmbedJson.MODEL, 768,
                Duration.ofSeconds(10));
        retriever = new HybridRetriever(new HybridRetrieverTest.FakeRepository(), embedder, RagProperties.defaults(),
                new SimpleMeterRegistry());
    }

    @AfterEach
    void close() {
        retriever.close();
        embedder.close();
    }

    @Test
    void ac_t10_11_theQueryEmbeddingRequestCarriesTheSearchQueryPrefix() {
        for (RetrievalMode mode : new RetrievalMode[]{RetrievalMode.HYBRID, RetrievalMode.VECTOR}) {
            retriever.retrieve(new RetrievalQuery("t1", "k8s", "how do Job retries work?", Set.of("eng"), 5, mode));
        }

        ollama.verify(2, postRequestedFor(urlEqualTo("/api/embed")).withRequestBody(equalToJson("""
                {"model": "nomic-embed-text", "input": ["search_query: how do Job retries work?"], "truncate": false}
                """)));
        assertThat(ollama.getAllServeEvents()).hasSize(2);
    }

    @Test
    void keywordModeMakesNoEmbedRequest() {
        retriever.retrieve(new RetrievalQuery("t1", "k8s", "how do Job retries work?", Set.of("eng"), 5,
                RetrievalMode.KEYWORD));

        assertThat(ollama.getAllServeEvents()).isEmpty();
    }
}
