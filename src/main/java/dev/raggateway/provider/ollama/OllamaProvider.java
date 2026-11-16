package dev.raggateway.provider.ollama;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
import dev.raggateway.config.GatewayProperties.ProviderProperties;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.Usage;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.provider.ProviderException;
import dev.raggateway.provider.ProviderException.Kind;
import dev.raggateway.provider.StreamWatchdog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Ollama adapter on the JDK {@link HttpClient} over HTTP/1.1, native {@code POST /api/chat} with
 * {@code stream: true} (t03, DECISIONS.md D-04, D-21). Shapes follow the official Ollama API docs (the
 * {@code support/OllamaNdjson} test fixture names the source); Ollama is not reachable in the build environment,
 * so the adapter is tested against WireMock only.
 *
 * <ul>
 *   <li>Request: {@code {"model", "messages":[{"role","content"}], "stream":true, "options":{...}}} with the
 *       catalog {@code upstream-id} as model; {@code options.num_predict} = max tokens, and
 *       {@code temperature}, {@code top_p}, {@code stop} only when the caller set them (Ollama accepts them,
 *       unlike Claude, D-05). No {@code think} field.</li>
 *   <li>Stream: {@code application/x-ndjson}, one JSON object per line. Each non-empty
 *       {@code message.content} is a token. The line with {@code done:true} ends the stream: {@code done_reason}
 *       {@code length} → {@link FinishReason#LENGTH}, anything else → {@link FinishReason#STOP};
 *       {@code prompt_eval_count} / {@code eval_count} → input / output tokens (absent counts are 0: Ollama
 *       omits zero values, e.g. a fully cached prompt). A stream that ends without {@code done:true}, or a
 *       line that is not JSON, is {@link Kind#MALFORMED_RESPONSE} (retryable).</li>
 *   <li>Errors: an HTTP error status carries {@code {"error":"..."}}; 429 and 5xx are retryable (with
 *       {@code Retry-After} in seconds), any other 4xx is not. 404 (model not pulled) is a configuration
 *       fault: not retried, but the router may move on to the next provider (D-37). An {@code {"error":...}} line inside
 *       a 200 stream is classified like Claude's in-stream error event: {@link Kind#HTTP_STATUS} 500,
 *       retryable (only before the first token, which the router checks). Connection refused or reset is
 *       {@link Kind#CONNECTION}, a connect timeout {@link Kind#CONNECT_TIMEOUT}; both retryable.</li>
 *   <li>Timeouts: a {@link StreamWatchdog}. The first-token clock runs to the first NDJSON line (Ollama sends
 *       nothing until the model has loaded and produced its first token; 30 s in config for a cold load);
 *       after that every line moves the idle deadline. Before the headers the watchdog cancels the
 *       {@code sendAsync} future, which makes the JDK client abort the exchange and close the connection;
 *       after them it closes the body stream, which does the same and unblocks the reading thread.</li>
 *   <li>Cancel: when the sink throws {@link ClientDisconnectedException}, the body stream is closed (closing the
 *       connection) and the exception is rethrown unchanged.</li>
 * </ul>
 */
public final class OllamaProvider implements LlmProvider {

    public static final String NAME = "ollama";
    public static final String NDJSON = "application/x-ndjson";

    static final Duration DEFAULT_FIRST_TOKEN_TIMEOUT = Duration.ofSeconds(30);
    static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofSeconds(30);
    /** Longest error body read; Ollama's are one short JSON object. */
    private static final int MAX_ERROR_BODY = 8 * 1024;
    private static final int MAX_ERROR_MESSAGE = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI chatUri;
    private final Map<String, ModelProperties> models;
    private final Duration firstTokenTimeout;
    private final Duration idleTimeout;

    public OllamaProvider(HttpClient http, GatewayProperties properties) {
        this.http = Objects.requireNonNull(http, "http");
        this.models = properties.models();
        ProviderProperties p = Objects.requireNonNull(properties.providers().get(NAME),
                "gateway.providers.ollama is not configured");
        String base = Objects.requireNonNull(p.baseUrl(), "gateway.providers.ollama.base-url");
        this.chatUri = URI.create(base.endsWith("/") ? base + "api/chat" : base + "/api/chat");
        this.firstTokenTimeout = p.firstTokenTimeout() != null ? p.firstTokenTimeout() : DEFAULT_FIRST_TOKEN_TIMEOUT;
        this.idleTimeout = p.idleTimeout() != null ? p.idleTimeout() : DEFAULT_IDLE_TIMEOUT;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean supports(String model) {
        ModelProperties m = models.get(model);
        return m != null && NAME.equals(m.provider());
    }

    @Override
    public void stream(ChatRequest request, StreamSink sink) throws ProviderException {
        HttpRequest httpRequest = HttpRequest.newBuilder(chatUri)
                .header("Content-Type", "application/json")
                .header("Accept", NDJSON)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request), StandardCharsets.UTF_8))
                .build();
        try (StreamWatchdog watchdog = StreamWatchdog.start(NAME, firstTokenTimeout, idleTimeout)) {
            try {
                streamGuarded(httpRequest, request, sink, watchdog);
            } catch (ClientDisconnectedException e) {
                throw e;
            } catch (ProviderException | RuntimeException e) {
                // The watchdog closed the call: whatever the read failed with, the cause is the timeout.
                if (watchdog.fired()) throw watchdog.timeout(e);
                throw e;
            }
        }
    }

    private void streamGuarded(HttpRequest httpRequest, ChatRequest request, StreamSink sink, StreamWatchdog watchdog)
            throws ProviderException {
        HttpResponse<InputStream> response = send(httpRequest, watchdog);
        try (InputStream body = response.body()) {
            watchdog.guard(() -> closeQuietly(body));
            int status = response.statusCode();
            if (status < 200 || status >= 300) throw httpError(response, body);

            Completion completion = readStream(body, request, sink, watchdog);
            watchdog.close(); // the upstream stream is finished; a slow client write is not an upstream timeout
            sink.onComplete(completion);
        } catch (IOException e) {
            throw new ProviderException(Kind.CONNECTION, "connection to Ollama failed while streaming", true, 0, null, e);
        }
        // ClientDisconnectedException is not caught: try-with-resources has already closed the body (and with
        // it the connection) when it leaves this method, unchanged (LlmProvider contract).
    }

    /** Sends the request and waits for the headers; the watchdog can cancel the wait and the exchange. */
    private HttpResponse<InputStream> send(HttpRequest httpRequest, StreamWatchdog watchdog) throws ProviderException {
        CompletableFuture<HttpResponse<InputStream>> pending =
                http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        watchdog.guard(() -> pending.cancel(true));
        try {
            return pending.get();
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderException(Kind.CONNECTION, "interrupted while waiting for Ollama", false, 0, null, e);
        } catch (CancellationException e) {
            throw new ProviderException(Kind.CONNECTION, "Ollama request cancelled", true, 0, null, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof HttpConnectTimeoutException) {
                throw new ProviderException(Kind.CONNECT_TIMEOUT, "connecting to Ollama timed out", true, 0, null, cause);
            }
            String what = cause instanceof ConnectException ? "connection to Ollama refused" : "connection to Ollama failed";
            throw new ProviderException(Kind.CONNECTION, what, true, 0, null, cause);
        }
    }

    /** Reads NDJSON lines until {@code done:true}; forwards each piece of content. */
    private Completion readStream(InputStream body, ChatRequest request, StreamSink sink, StreamWatchdog watchdog)
            throws IOException, ProviderException {
        var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        boolean first = true;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            if (first) {
                watchdog.contentStarted();
                first = false;
            } else {
                watchdog.activity();
            }
            JsonNode node = parseLine(line);
            if (node.hasNonNull("error")) {
                throw new ProviderException(Kind.HTTP_STATUS,
                        "Ollama stream error: " + shorten(node.get("error").asText()), true, 500, null, null);
            }
            String content = node.path("message").path("content").asText("");
            if (!content.isEmpty()) {
                sink.onToken(content);
                watchdog.activity(); // time spent writing to the client is not upstream silence
            }
            if (node.path("done").asBoolean(false)) {
                drain(body);
                return completion(node, request);
            }
        }
        throw new ProviderException(Kind.MALFORMED_RESPONSE, "Ollama stream ended without done:true", true, 0, null,
                null);
    }

    /**
     * Reads what is left after {@code done:true} (normally just the end of the chunked body), so the JDK client
     * can return the connection to its pool instead of closing it. Bounded by the idle watchdog, which is still
     * armed; an error here is ignored because the answer is already complete.
     */
    private static void drain(InputStream body) {
        try {
            body.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // the stream is complete; a failure to drain only costs connection reuse
        }
    }

    private Completion completion(JsonNode done, ChatRequest request) {
        FinishReason finish = "length".equals(done.path("done_reason").asText()) ? FinishReason.LENGTH : FinishReason.STOP;
        var usage = new Usage(done.path("prompt_eval_count").asInt(0), done.path("eval_count").asInt(0));
        String model = done.path("model").asText("");
        return new Completion(finish, usage, model.isEmpty() ? upstreamId(request) : model);
    }

    private static JsonNode parseLine(String line) throws ProviderException {
        try {
            JsonNode node = JSON.readTree(line);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("not a JSON object");
            return node;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new ProviderException(Kind.MALFORMED_RESPONSE, "unreadable Ollama stream line", true, 0, null, e);
        }
    }

    private static ProviderException httpError(HttpResponse<InputStream> response, InputStream body) throws IOException {
        int status = response.statusCode();
        String detail = "";
        byte[] bytes = body.readNBytes(MAX_ERROR_BODY);
        try {
            JsonNode node = JSON.readTree(bytes);
            if (node != null && node.hasNonNull("error")) detail = ": " + shorten(node.get("error").asText());
        } catch (IOException notJson) {
            // keep the status only
        }
        boolean retryable = status == 429 || status >= 500;
        Duration retryAfter = retryable ? retryAfter(response) : null;
        return new ProviderException(Kind.HTTP_STATUS, "Ollama returned HTTP " + status + detail, retryable, status,
                retryAfter, null);
    }

    /** {@code Retry-After} as delta-seconds; null when absent or not a number. */
    static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("retry-after").map(String::trim).map(v -> {
            try {
                long seconds = Long.parseLong(v);
                return seconds < 0 ? null : Duration.ofSeconds(seconds);
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }

    /** The {@code /api/chat} request body. Package-private for tests. */
    String requestBody(ChatRequest request) {
        ObjectNode root = JSON.createObjectNode();
        root.put("model", upstreamId(request));
        ArrayNode messages = root.putArray("messages");
        for (Message m : request.messages()) {
            messages.addObject().put("role", m.role().name().toLowerCase(Locale.ROOT)).put("content", m.content());
        }
        root.put("stream", true);
        ObjectNode options = root.putObject("options");
        if (request.temperature() != null) options.put("temperature", request.temperature());
        if (request.topP() != null) options.put("top_p", request.topP());
        options.put("num_predict", request.maxTokens());
        if (!request.stop().isEmpty()) {
            ArrayNode stop = options.putArray("stop");
            request.stop().forEach(stop::add);
        }
        return root.toString();
    }

    private String upstreamId(ChatRequest request) {
        ModelProperties model = models.get(request.model());
        return model != null && model.upstreamId() != null ? model.upstreamId() : request.model();
    }

    private static String shorten(String message) {
        return message.length() <= MAX_ERROR_MESSAGE ? message : message.substring(0, MAX_ERROR_MESSAGE) + "...";
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // abandoning the stream; nothing more to do
        }
    }
}
