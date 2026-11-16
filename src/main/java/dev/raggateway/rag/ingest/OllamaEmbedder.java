package dev.raggateway.rag.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.raggateway.rag.Embedder;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code nomic-embed-text} through Ollama's native {@code POST /api/embed} (t09, DECISIONS.md D-04, D-47), on the
 * JDK {@link HttpClient}. Shapes follow the official API docs (the {@code support/OllamaEmbedJson} test fixture
 * names the source); Ollama is not reachable in the build environment, so this is tested against WireMock only.
 * <ul>
 *   <li>Request: {@code {"model", "input": [...], "truncate": false}}, one call per batch (the caller bounds the
 *       batch, {@code gateway.rag.embed-batch-size}). {@code truncate:false} because the API's default cuts an
 *       over-long input silently, and a vector for text that is not the chunk is worse than an error.</li>
 *   <li>Task prefixes (D-47): every document input is sent as {@code search_document: <text>}, a query as
 *       {@code search_query: <text>}. The worker passes {@code <title>\n\n<chunk text>} as the text.</li>
 *   <li>Response: {@code {"embeddings": [[...], ...]}} in input order. Anything but exactly one vector per input,
 *       each of {@link #dimensions()} finite values, fails the whole batch.</li>
 *   <li>Errors: a non-2xx status (Ollama sends {@code {"error": "..."}}), a refused or timed-out connection
 *       (per-call timeout, default 60 s), and an unreadable body all throw {@link EmbeddingException} naming
 *       the cause; the worker releases the job with that message.</li>
 * </ul>
 * Holds no state besides the client; thread-safe. {@link #close()} closes the client it was given.
 */
public final class OllamaEmbedder implements Embedder, AutoCloseable {

    public static final String DOCUMENT_PREFIX = "search_document: ";
    public static final String QUERY_PREFIX = "search_query: ";
    /** Longest response read. 20 vectors of 768 values are about 0.3 MB of JSON. */
    static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ERROR_MESSAGE = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI embedUri;
    private final String model;
    private final int dimensions;
    private final Duration timeout;

    public OllamaEmbedder(HttpClient http, String baseUrl, String model, int dimensions, Duration timeout) {
        this.http = Objects.requireNonNull(http, "http");
        Objects.requireNonNull(baseUrl, "gateway.providers.ollama.base-url");
        this.embedUri = URI.create(baseUrl.endsWith("/") ? baseUrl + "api/embed" : baseUrl + "/api/embed");
        this.model = Objects.requireNonNull(model, "model");
        if (dimensions <= 0) throw new IllegalArgumentException("dimensions must be > 0");
        this.dimensions = dimensions;
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        return embed(texts.stream().map(t -> DOCUMENT_PREFIX + t).toList());
    }

    @Override
    public float[] embedQuery(String text) {
        return embed(List.of(QUERY_PREFIX + text)).getFirst();
    }

    @Override
    public void close() {
        http.close();
    }

    private List<float[]> embed(List<String> inputs) {
        HttpRequest request = HttpRequest.newBuilder(embedUri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(inputs), StandardCharsets.UTF_8))
                .build();
        HttpResponse<InputStream> response = send(request);
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new EmbeddingException("Ollama embed response is larger than " + MAX_RESPONSE_BYTES + " bytes");
            }
            int status = response.statusCode();
            if (status < 200 || status >= 300) throw httpError(status, bytes);
            return vectors(bytes, inputs.size());
        } catch (HttpTimeoutException e) {
            throw new EmbeddingException("Ollama embed timed out after " + timeout.toMillis() + " ms", e);
        } catch (IOException e) {
            throw new EmbeddingException("connection to Ollama failed while reading the embed response", e);
        }
    }

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw new EmbeddingException("connecting to Ollama timed out", e);
        } catch (HttpTimeoutException e) {
            throw new EmbeddingException("Ollama embed timed out after " + timeout.toMillis() + " ms", e);
        } catch (ConnectException e) {
            throw new EmbeddingException("connection to Ollama refused", e);
        } catch (IOException e) {
            throw new EmbeddingException("connection to Ollama failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("interrupted while waiting for Ollama", e);
        }
    }

    /** The {@code /api/embed} body. Package-private for tests. */
    String requestBody(List<String> inputs) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", model);
        ArrayNode input = root.putArray("input");
        inputs.forEach(input::add);
        root.put("truncate", false);
        return root.toString();
    }

    private List<float[]> vectors(byte[] body, int expected) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new EmbeddingException("unreadable Ollama embed response", e);
        }
        JsonNode embeddings = root == null ? null : root.path("embeddings");
        if (embeddings == null) throw new EmbeddingException("empty Ollama embed response");
        if (!embeddings.isArray()) throw new EmbeddingException("Ollama embed response has no embeddings array");
        if (embeddings.size() != expected) {
            throw new EmbeddingException("Ollama returned " + embeddings.size() + " vectors for " + expected + " inputs");
        }
        List<float[]> out = new ArrayList<>(expected);
        for (int i = 0; i < expected; i++) {
            JsonNode row = embeddings.get(i);
            if (!row.isArray() || row.size() != dimensions) {
                throw new EmbeddingException("Ollama vector " + i + " has " + (row.isArray() ? row.size() : 0)
                        + " values, expected " + dimensions);
            }
            float[] v = new float[dimensions];
            for (int j = 0; j < dimensions; j++) {
                JsonNode x = row.get(j);
                float f = (float) x.asDouble(Double.NaN);
                if (!x.isNumber() || !Float.isFinite(f)) {
                    throw new EmbeddingException("Ollama vector " + i + " has a non-finite value at " + j);
                }
                v[j] = f;
            }
            out.add(v);
        }
        return out;
    }

    private static EmbeddingException httpError(int status, byte[] body) {
        String detail = "";
        try {
            JsonNode node = JSON.readTree(body);
            if (node != null && node.hasNonNull("error")) detail = ": " + shorten(node.get("error").asText());
        } catch (IOException notJson) {
            // keep the status only
        }
        return new EmbeddingException("Ollama embed returned HTTP " + status + detail);
    }

    private static String shorten(String message) {
        return message.length() <= MAX_ERROR_MESSAGE ? message : message.substring(0, MAX_ERROR_MESSAGE) + "...";
    }

    /** An embed call failed; the whole batch is lost and the job is retried later. */
    public static final class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) {
            super(message);
        }

        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
