package dev.raggateway.provider.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.http.Headers;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicRetryableException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.SseException;
import com.anthropic.models.ErrorType;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.RawContentBlockDelta;
import com.anthropic.models.messages.RawMessageDeltaEvent;
import com.anthropic.models.messages.RawMessageStartEvent;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.StopReason;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
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

import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Claude adapter on the official Anthropic Java SDK (DECISIONS.md D-04, D-05, D-06). A translation layer
 * only: the SDK owns authentication, the API version header, SSE parsing and typed errors.
 *
 * <ul>
 *   <li>Request: model id from the catalog ({@code upstream-id}), {@code max_tokens}, system messages joined
 *       into {@code system}, user/assistant turns in order, {@code stop} as {@code stop_sequences}.
 *       {@code temperature} and {@code top_p} are never sent: API version 2026-10-21 rejects them (D-05).
 *       No {@code thinking}, {@code effort} or server-side fallback field (D-06).</li>
 *   <li>Stream: only {@code text_delta} content becomes tokens; thinking blocks are skipped. Input tokens come
 *       from {@code message_start}, cumulative output tokens and the stop reason from the last
 *       {@code message_delta}. A stream without {@code message_start} or a stop reason is
 *       {@link Kind#MALFORMED_RESPONSE}.</li>
 *   <li>Errors: HTTP 429 and 5xx (and an in-stream {@code overloaded_error}/{@code api_error}) are retryable
 *       with {@code retry-after} parsed; other 4xx are not. I/O failures are {@link Kind#CONNECTION}.</li>
 *   <li>Cancel: when the sink throws {@link ClientDisconnectedException}, the {@link StreamResponse} is closed
 *       (which cancels the HTTP call and closes the socket) and the exception is rethrown unchanged.</li>
 * </ul>
 *
 * The first-token and idle watchdogs are added in t03; until then the SDK read timeout bounds a silent stream.
 */
public final class ClaudeProvider implements LlmProvider {

    public static final String NAME = "claude";

    private final AnthropicClient client;
    private final Map<String, ModelProperties> models;
    private final Clock clock;

    public ClaudeProvider(AnthropicClient client, GatewayProperties properties) {
        this(client, properties, Clock.systemUTC());
    }

    ClaudeProvider(AnthropicClient client, GatewayProperties properties, Clock clock) {
        this.client = Objects.requireNonNull(client, "client");
        this.models = properties.models();
        this.clock = Objects.requireNonNull(clock, "clock");
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
        MessageCreateParams params = toParams(request);
        var state = new StreamState();
        try (StreamResponse<RawMessageStreamEvent> response = client.messages().createStreaming(params)) {
            Iterator<RawMessageStreamEvent> events = response.iterator();
            while (events.hasNext()) {
                RawMessageStreamEvent event = events.next();
                if (state.accept(event, sink)) break;
            }
            sink.onComplete(state.completion());
        } catch (AnthropicServiceException e) {
            throw fromServiceException(e);
        } catch (AnthropicIoException | AnthropicRetryableException | UncheckedIOException e) {
            throw new ProviderException(Kind.CONNECTION, "connection to Claude failed", true, 0, null, e);
        } catch (AnthropicInvalidDataException e) {
            throw new ProviderException(Kind.MALFORMED_RESPONSE, "unreadable Claude stream event", true, 0, null, e);
        } catch (AnthropicException e) {
            // Client-side SDK failure (e.g. missing credentials): retrying the same call cannot help.
            throw new ProviderException(Kind.CONNECTION, "Claude client error: " + e.getClass().getSimpleName(),
                    false, 0, null, e);
        }
        // ClientDisconnectedException is not caught: try-with-resources has already closed the response
        // (cancelling the upstream call) when it leaves this method, unchanged (LlmProvider contract).
    }

    /** Builds the Messages request. Package-private for tests. */
    MessageCreateParams toParams(ChatRequest request) {
        ModelProperties model = models.get(request.model());
        String upstreamId = model != null && model.upstreamId() != null ? model.upstreamId() : request.model();
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(upstreamId)
                .maxTokens(request.maxTokens());
        List<String> system = new ArrayList<>();
        for (Message m : request.messages()) {
            switch (m.role()) {
                case SYSTEM -> system.add(m.content());
                case USER -> builder.addUserMessage(m.content());
                case ASSISTANT -> builder.addAssistantMessage(m.content());
            }
        }
        if (!system.isEmpty()) builder.system(String.join("\n\n", system));
        if (!request.stop().isEmpty()) builder.stopSequences(request.stop());
        // Deliberately absent: temperature, top_p (D-05); thinking, effort, fallbacks (D-06).
        return builder.build();
    }

    private ProviderException fromServiceException(AnthropicServiceException e) {
        int status = e.statusCode();
        String type = e.errorType().map(ErrorType::asString).orElse(null);
        if (e instanceof SseException) {
            // An "event: error" inside a 200 stream: classify by the error type in the event.
            status = switch (type == null ? "" : type) {
                case "overloaded_error" -> 529;
                case "rate_limit_error" -> 429;
                case "api_error", "timeout_error" -> 500;
                default -> status >= 400 ? status : 500;
            };
        }
        boolean retryable = status == 429 || status >= 500;
        String message = "Claude returned HTTP " + status + (type == null ? "" : " (" + type + ")");
        return new ProviderException(Kind.HTTP_STATUS, message, retryable, status,
                retryAfter(e.headers()), e);
    }

    /** Parses {@code retry-after} as delta-seconds or an HTTP date; null when absent or unreadable. */
    Duration retryAfter(Headers headers) {
        List<String> values = headers == null ? List.of() : headers.values("retry-after");
        if (values.isEmpty()) return null;
        String value = values.get(0).trim();
        try {
            double seconds = Double.parseDouble(value);
            if (seconds < 0 || Double.isNaN(seconds) || Double.isInfinite(seconds)) return null;
            return Duration.ofMillis((long) Math.ceil(seconds * 1000));
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration d = Duration.between(clock.instant(), at);
                return d.isNegative() ? Duration.ZERO : d;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }

    static FinishReason finishReason(String stopReason) {
        return switch (stopReason) {
            case "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH;
            case "refusal" -> FinishReason.CONTENT_FILTER;
            // end_turn, stop_sequence; tool_use/pause_turn cannot occur without tools
            default -> FinishReason.STOP;
        };
    }

    /** Accumulates usage, stop reason and model across the events of one stream. */
    private static final class StreamState {
        private boolean started;
        private long inputTokens;
        private long outputTokens;
        private String model;
        private String stopReason;

        /** @return true when the stream is finished ({@code message_stop}) */
        boolean accept(RawMessageStreamEvent event, StreamSink sink) {
            if (event.isMessageStart()) {
                RawMessageStartEvent start = event.asMessageStart();
                started = true;
                model = start.message().model().asString();
                inputTokens = start.message().usage().inputTokens();
                outputTokens = start.message().usage().outputTokens();
            } else if (event.isContentBlockDelta()) {
                RawContentBlockDelta delta = event.asContentBlockDelta().delta();
                // Only text deltas are forwarded; thinking and signature deltas are not (D-06).
                if (delta.isText()) {
                    String text = delta.asText().text();
                    if (!text.isEmpty()) sink.onToken(text);
                }
            } else if (event.isMessageDelta()) {
                RawMessageDeltaEvent md = event.asMessageDelta();
                md.delta().stopReason().map(StopReason::asString).ifPresent(r -> stopReason = r);
                outputTokens = md.usage().outputTokens();       // cumulative
                md.usage().inputTokens().filter(in -> in > 0).ifPresent(in -> inputTokens = in);
            } else if (event.isMessageStop()) {
                return true;
            }
            return false;
        }

        Completion completion() throws ProviderException {
            if (!started || stopReason == null) {
                throw new ProviderException(Kind.MALFORMED_RESPONSE,
                        started ? "Claude stream ended without a stop reason" : "Claude stream had no message_start",
                        true, 0, null, null);
            }
            return new Completion(finishReason(stopReason),
                    new Usage(Math.toIntExact(inputTokens), Math.toIntExact(outputTokens)), model);
        }
    }
}
