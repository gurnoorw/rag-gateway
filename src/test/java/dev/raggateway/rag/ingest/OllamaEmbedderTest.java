package dev.raggateway.rag.ingest;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.raggateway.config.GatewayProperties.ProviderProperties;
import dev.raggateway.provider.ollama.OllamaClientConfig;
import dev.raggateway.rag.ingest.OllamaEmbedder.EmbeddingException;
import dev.raggateway.support.OllamaEmbedJson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Ollama embedder against WireMock speaking {@code /api/embed} ({@link OllamaEmbedJson}, shapes from the
 * official docs; Ollama is not reachable here, D-21). AC-t09-8.
 */
class OllamaEmbedderTest {

    static final String MODEL = OllamaEmbedJson.MODEL;
    static WireMockServer wireMock;
    OllamaEmbedder embedder;

    @BeforeAll
    static void start() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        embedder = embedder(wireMock.baseUrl(), Duration.ofSeconds(10));
    }

    @AfterEach
    void close() {
        embedder.close();
    }

    static OllamaEmbedder embedder(String baseUrl, Duration timeout) {
        var props = new ProviderProperties(baseUrl, null, Duration.ofSeconds(1), null, null);
        return new OllamaEmbedder(OllamaClientConfig.client(props), baseUrl, MODEL, 768, timeout);
    }

    @Test
    void ac_t09_8_documentsArePostedAsOneBatchWithThePrefixAndNoTruncation() {
        List<float[]> vectors = List.of(OllamaEmbedJson.vector(768, 1), OllamaEmbedJson.vector(768, 2));
        wireMock.stubFor(post("/api/embed").willReturn(okJson(OllamaEmbedJson.response(MODEL, vectors))));

        List<float[]> out = embedder.embedDocuments(List.of("Jobs\n\nA job runs to completion.", "Jobs\n\nRetries."));

        assertThat(out).hasSize(2);
        assertThat(out.get(0)).containsExactly(vectors.get(0));
        assertThat(out.get(1)).containsExactly(vectors.get(1));
        wireMock.verify(1, postRequestedFor(urlEqualTo("/api/embed"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"model": "nomic-embed-text",
                         "input": ["search_document: Jobs\\n\\nA job runs to completion.",
                                   "search_document: Jobs\\n\\nRetries."],
                         "truncate": false}
                        """)));
    }

    @Test
    void ac_t09_8_queryUsesTheQueryPrefix() {
        wireMock.stubFor(post("/api/embed").willReturn(okJson(OllamaEmbedJson.response(MODEL, 1, 768))));

        float[] v = embedder.embedQuery("how do retries work?");

        assertThat(v).hasSize(768);
        wireMock.verify(postRequestedFor(urlEqualTo("/api/embed")).withRequestBody(equalToJson("""
                {"model": "nomic-embed-text", "input": ["search_query: how do retries work?"], "truncate": false}
                """)));
    }

    @Test
    void ac_t09_8_rejectsAResponseWithTheWrongVectorCount() {
        wireMock.stubFor(post("/api/embed").willReturn(okJson(OllamaEmbedJson.response(MODEL, 1, 768))));

        assertThatThrownBy(() -> embedder.embedDocuments(List.of("a", "b", "c")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("1 vectors for 3 inputs");
    }

    @Test
    void ac_t09_8_rejectsAVectorWhoseLengthIsNot768() {
        wireMock.stubFor(post("/api/embed").willReturn(okJson(OllamaEmbedJson.response(MODEL, 2, 1024))));

        assertThatThrownBy(() -> embedder.embedDocuments(List.of("a", "b")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("1024 values, expected 768");
    }

    @Test
    void ac_t09_8_rejectsWhenOnlyOneVectorHasTheWrongLength() {
        String body = OllamaEmbedJson.response(MODEL,
                List.of(OllamaEmbedJson.vector(768, 1), OllamaEmbedJson.vector(767, 2)));
        wireMock.stubFor(post("/api/embed").willReturn(okJson(body)));

        assertThatThrownBy(() -> embedder.embedDocuments(List.of("a", "b")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("vector 1 has 767 values");
    }

    @Test
    void ac_t09_8_errorStatusBecomesAnEmbeddingFailureNamingTheStatus() {
        wireMock.stubFor(post("/api/embed").willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody(OllamaEmbedJson.error("model \"nomic-embed-text\" not found, try pulling it first"))));

        assertThatThrownBy(() -> embedder.embedDocuments(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("HTTP 404")
                .hasMessageContaining("not found, try pulling it first");
    }

    @Test
    void serverErrorWithoutJsonStillNamesTheStatus() {
        wireMock.stubFor(post("/api/embed").willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> embedder.embedDocuments(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessage("Ollama embed returned HTTP 500");
    }

    @Test
    void unreadableBodyFails() {
        wireMock.stubFor(post("/api/embed").willReturn(okJson("{\"embeddings\": [[1, 2")));

        assertThatThrownBy(() -> embedder.embedDocuments(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("unreadable");
    }

    @Test
    void slowResponseTimesOut() {
        wireMock.stubFor(post("/api/embed").willReturn(okJson(OllamaEmbedJson.response(MODEL, 1, 768))
                .withFixedDelay(3000)));
        try (OllamaEmbedder quick = embedder(wireMock.baseUrl(), Duration.ofMillis(300))) {
            assertThatThrownBy(() -> quick.embedDocuments(List.of("a")))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("timed out");
        }
    }

    @Test
    void refusedConnectionFails() throws IOException {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        try (OllamaEmbedder down = embedder("http://127.0.0.1:" + port, Duration.ofSeconds(2))) {
            assertThatThrownBy(() -> down.embedDocuments(List.of("a")))
                    .isInstanceOf(EmbeddingException.class)
                    .hasMessageContaining("Ollama");
        }
    }

    @Test
    void emptyBatchMakesNoCall() {
        assertThat(embedder.embedDocuments(List.of())).isEmpty();
        wireMock.verify(0, postRequestedFor(urlEqualTo("/api/embed")));
    }
}
