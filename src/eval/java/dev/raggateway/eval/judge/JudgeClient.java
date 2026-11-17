package dev.raggateway.eval.judge;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.validation.NetworkntOutputValidator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * The answer judge's own Claude client (D-24: not the gateway, not its cache, not its metrics).
 *
 * <p><b>Building it.</b> Only {@link #create} builds the SDK client, and {@link AnswerEvalRunner} calls it only with
 * {@code --confirm-spend}. The key is read from {@link JudgeSettings#API_KEY_ENV} and nowhere else: the client is
 * built explicitly (never {@code fromEnv()}, which would read {@code ANTHROPIC_API_KEY}, profiles and other
 * credentials). Like the gateway's adapter (D-29), the key is set with {@code apiKey(..)} and also sent as an
 * explicit {@code X-Api-Key} header, because SDK 3.3.0 otherwise sends a static key as {@code Authorization:
 * Bearer}. {@code maxRetries(0)}: every request the SDK sends is one the spend cap has accounted for.
 *
 * <p><b>The request</b> ({@link #params}): model, {@code max_tokens} and {@code output_config.effort} from
 * {@code judge.properties}; the verdict format from {@code outputConfig(JudgeVerdict.class)} (claude-api skill, Java
 * {@code tool-use.md}, "Structured Output"). No {@code temperature}, {@code top_p} or {@code top_k}: Claude Haiku 5.5
 * rejects non-default sampling. No {@code thinking} field: the model runs adaptive thinking by default, and
 * {@code max_tokens} leaves room for it.
 *
 * <p><b>The response</b> ({@link #read}): content blocks are read by type. Thinking and redacted-thinking blocks
 * (which may come first) are skipped; exactly one text block must remain. Its raw text is checked against
 * {@link JudgeVerdict#SCHEMA} and only then mapped to a {@link JudgeVerdict}. A refusal, a truncated reply
 * ({@code max_tokens}), no text block, JSON that does not parse, or a value out of range is a {@link Rejected}
 * outcome, reported and never counted as a score. Usage is returned either way, so the spend cap charges it.
 */
public final class JudgeClient implements AutoCloseable {

    /** The Messages API. Tests pass WireMock's URL instead. */
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    static final String API_KEY_HEADER = "X-Api-Key";

    private final AnthropicClient client;
    private final JudgeSettings settings;
    private final ObjectMapper json = new ObjectMapper();
    private final NetworkntOutputValidator validator = new NetworkntOutputValidator(json);

    private JudgeClient(AnthropicClient client, JudgeSettings settings) {
        this.client = client;
        this.settings = settings;
    }

    /**
     * Builds the client. {@code env} is the process environment in production ({@code System::getenv}); only
     * {@link JudgeSettings#apiKeyEnv()} is looked up in it.
     *
     * @throws IllegalStateException when the key variable is unset or blank
     */
    public static JudgeClient create(JudgeSettings settings, String baseUrl, Function<String, String> env) {
        Objects.requireNonNull(settings, "settings");
        String key = env.apply(settings.apiKeyEnv());
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(settings.apiKeyEnv() + " is not set; the judge reads its key from that"
                    + " variable only");
        }
        AnthropicClient sdk = AnthropicOkHttpClient.builder()
                .baseUrl(baseUrl == null ? DEFAULT_BASE_URL : baseUrl)
                .apiKey(key)
                .putHeader(API_KEY_HEADER, key)
                .maxRetries(0)
                .timeout(Duration.ofMinutes(2))
                .build();
        return new JudgeClient(sdk, settings);
    }

    /** A judge prompt: the fixed system message and the filled-in user message. */
    public record Prompt(String system, String user) {
        public Prompt {
            Objects.requireNonNull(system, "system");
            Objects.requireNonNull(user, "user");
        }
    }

    /** Token usage a response reported. Cache tokens, if any, are counted with the input. */
    public record Usage(long inputTokens, long outputTokens) {
    }

    /** What one call produced. */
    public sealed interface Outcome permits Scored, Rejected {
        Usage usage();
    }

    /** A verdict that passed the schema. */
    public record Scored(JudgeVerdict verdict, Usage usage) implements Outcome {
    }

    /** A response that is not a valid verdict, with the reason (built from the schema side, never the reply). */
    public record Rejected(String reason, Usage usage) implements Outcome {
    }

    /** A call that failed before a response was read (HTTP error, I/O, timeout). Its usage is unknown. */
    public static final class JudgeCallException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        JudgeCallException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** One judge call. */
    public Outcome judge(Prompt prompt) {
        StructuredMessage<JudgeVerdict> response;
        try {
            response = client.messages().create(params(settings, prompt));
        } catch (AnthropicServiceException e) {
            throw new JudgeCallException("judge call failed with HTTP " + e.statusCode()
                    + e.errorType().map(t -> " (" + t.asString() + ")").orElse(""), e);
        } catch (AnthropicException e) {
            throw new JudgeCallException("judge call failed: " + e.getClass().getSimpleName(), e);
        }
        return read(response.rawMessage());
    }

    /** The request for one prompt (see the class comment). Package-visible for the request-shape tests. */
    static StructuredMessageCreateParams<JudgeVerdict> params(JudgeSettings settings, Prompt prompt) {
        return MessageCreateParams.builder()
                .model(settings.model())
                .maxTokens(settings.maxTokens())
                .system(prompt.system())
                .addUserMessage(prompt.user())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(settings.effort())).build())
                .outputConfig(JudgeVerdict.class)
                .build();
    }

    /** Reads a response by content-block type and validates the verdict (see the class comment). */
    Outcome read(Message message) {
        com.anthropic.models.messages.Usage u = message.usage();
        long input = u.inputTokens() + u.cacheCreationInputTokens().orElse(0L) + u.cacheReadInputTokens().orElse(0L);
        Usage usage = new Usage(input, u.outputTokens());

        StopReason stop = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop)) return new Rejected("the model refused (stop_reason refusal)", usage);
        if (StopReason.MAX_TOKENS.equals(stop)) {
            return new Rejected("the reply was cut off at max_tokens (stop_reason max_tokens)", usage);
        }

        List<String> texts = new ArrayList<>();
        for (ContentBlock block : message.content()) {
            if (block.isThinking() || block.isRedactedThinking()) continue;
            if (block.isText()) texts.add(block.asText().text());
            else return new Rejected("unexpected content block type " + block.type(), usage);
        }
        if (texts.size() != 1) return new Rejected("expected exactly one text block, got " + texts.size(), usage);

        String text = texts.get(0);
        List<String> errors = validator.validate(JudgeVerdict.SCHEMA, text);
        if (!errors.isEmpty()) return new Rejected("verdict failed its schema: " + String.join("; ", errors), usage);
        try {
            return new Scored(json.readValue(text, JudgeVerdict.class), usage);
        } catch (Exception e) {
            return new Rejected("verdict did not map to JudgeVerdict", usage);
        }
    }

    @Override
    public void close() {
        client.close();
    }
}
