package dev.raggateway.validation;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.RequestContext;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.pipeline.Terminal;
import dev.raggateway.resilience.FallbackRouter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * The primary {@link Terminal} (t07; DECISIONS.md C3, C8, D-14, D-43): output validation around the
 * {@link FallbackRouter}. A decorator of the terminal rather than a filter, because a retry has to call the provider
 * again and a filter may call {@code chain.next} only once.
 *
 * <p><b>No schema:</b> the client sink goes straight to the router. Tokens stream as they arrive (AC-t07-5).
 *
 * <p><b>Schema requested</b> ({@code response_format} of type {@code json_schema}):
 * <ol>
 *   <li>The router writes into a buffer; nothing reaches the client, so a bad answer can still be retried.</li>
 *   <li>On completion the answer is invalid when it ended by {@code length} ({@code TRUNCATED}: cut-off JSON,
 *       AC-t07-9), by {@code content_filter} ({@code REFUSED}), or does not validate against the schema
 *       ({@code SCHEMA}).</li>
 *   <li>Invalid: the attempt that produced it is marked {@code INVALID_OUTPUT} with that kind (its usage stays, so it
 *       is billed, AC-t07-8, and the cache never stores it), {@code gateway.validation.failures{kind=schema}} is
 *       counted, and the whole router call runs once more.</li>
 *   <li>Invalid again: 502 {@code invalid_model_output}. The message names at most {@value #MAX_ERRORS} schema errors
 *       and is at most {@value #MAX_MESSAGE} characters; it never quotes the output (the errors come from the schema
 *       side, see {@link NetworkntOutputValidator}), since the output may echo the prompt or other data.</li>
 *   <li>Valid: the buffer goes to the client as one {@code onToken}, then {@code onComplete}. With {@code stream:true}
 *       that is one content chunk; the client sees no tokens of an invalid answer (AC-t07-4).</li>
 * </ol>
 * A provider error inside a buffered call goes to the client exactly as the router reported it, with no validation
 * retry. Known limit (D-43): a failure in the middle of a buffered call is not retried by the router either, since
 * it counts the buffered tokens as sent; the client gets a 502 at once.
 *
 * <p>Every router call records its own attempts, so the log shows both calls and the tenant pays for both, and the
 * cache's store rule (serving attempt {@code SUCCEEDED}) holds unchanged: after a valid retry the serving attempt is
 * the second one; after two invalid answers the client sink sees an error, which is never stored (AC-t07-7).
 *
 * <p>A client that disconnects while the buffered answer is being written makes {@code onToken} throw; the exception
 * propagates (the pipeline records the request as cancelled). The attempt stays {@code SUCCEEDED} with the usage the
 * provider reported: the provider call itself finished before anything was written.
 */
@Component
@Primary
public final class ValidatingTerminal implements Terminal {

    private static final Logger log = LoggerFactory.getLogger(ValidatingTerminal.class);

    /** Router calls per request when a schema is requested: the first and one retry (C8). */
    static final int MAX_CALLS = 2;
    static final int MAX_ERRORS = 3;
    static final int MAX_MESSAGE = 300;

    /** {@code errorKind} values written on the invalid attempt (request log). */
    static final String TRUNCATED = "TRUNCATED";
    static final String REFUSED = "REFUSED";
    static final String SCHEMA = "SCHEMA";

    private final FallbackRouter router;
    private final OutputValidator validator;
    private final ValidationFailures failures;

    @Autowired
    public ValidatingTerminal(FallbackRouter router, OutputValidator validator, MeterRegistry meters,
                              GatewayProperties gateway) {
        this(router, validator, new ValidationFailures(meters, gateway.models().keySet()));
    }

    ValidatingTerminal(FallbackRouter router, OutputValidator validator, ValidationFailures failures) {
        this.router = Objects.requireNonNull(router, "router");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.failures = Objects.requireNonNull(failures, "failures");
    }

    @Override
    public void call(RequestContext ctx, StreamSink sink) {
        if (!ctx.request().hasSchema()) {
            router.call(ctx, sink);
            return;
        }
        String schema = ctx.request().jsonSchema();
        Verdict last = null;
        for (int call = 1; call <= MAX_CALLS; call++) {
            var buffer = new BufferingSink();
            router.call(ctx, buffer);
            if (buffer.error != null) {
                sink.onError(buffer.error);
                return;
            }
            if (buffer.completion == null) {
                // The router broke its contract (no terminal event); never send unvalidated output.
                sink.onError(new GatewayException(ErrorCode.INTERNAL_ERROR, "internal error"));
                return;
            }
            String output = buffer.text.toString();
            Verdict verdict;
            try {
                verdict = judge(schema, buffer.completion, output);
            } catch (GatewayException e) {
                sink.onError(e); // the schema itself failed to compile: unreachable after RequestValidationFilter
                return;
            }
            if (verdict.valid()) {
                if (!output.isEmpty()) sink.onToken(output);
                sink.onComplete(buffer.completion);
                return;
            }
            ctx.markLastAttemptInvalid(verdict.kind());
            String model = ctx.servingAttempt().map(Attempt::model).orElse(ctx.request().model());
            failures.increment(model, ValidationFailures.SCHEMA);
            log.info("request {}: router call {} returned invalid output from {} ({}, {} schema error(s))",
                    ctx.requestId(), call, model, verdict.kind(), verdict.errors().size());
            last = verdict;
        }
        sink.onError(new GatewayException(ErrorCode.INVALID_MODEL_OUTPUT, message(last)));
    }

    private Verdict judge(String schema, Completion completion, String output) {
        if (completion.finishReason() == FinishReason.LENGTH) return Verdict.invalid(TRUNCATED, List.of());
        if (completion.finishReason() == FinishReason.CONTENT_FILTER) return Verdict.invalid(REFUSED, List.of());
        List<String> errors = validator.validate(schema, output);
        return errors.isEmpty() ? Verdict.VALID : Verdict.invalid(SCHEMA, errors);
    }

    /** The 502 message: why the last answer failed, never its content. */
    static String message(Verdict last) {
        String reason = switch (last.kind()) {
            case TRUNCATED -> "it was cut off by max_tokens (finish reason length)";
            case REFUSED -> "the model refused (finish reason content_filter)";
            default -> {
                List<String> shown = last.errors().stream().limit(MAX_ERRORS).toList();
                String more = last.errors().size() > MAX_ERRORS
                        ? " (+" + (last.errors().size() - MAX_ERRORS) + " more)" : "";
                yield "it does not match the schema: " + String.join("; ", shown) + more;
            }
        };
        String message = "model output failed validation after " + MAX_CALLS + " attempts; last answer: " + reason;
        return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE - 3) + "...";
    }

    record Verdict(String kind, List<String> errors) {
        static final Verdict VALID = new Verdict(null, List.of());

        static Verdict invalid(String kind, List<String> errors) {
            return new Verdict(kind, List.copyOf(errors));
        }

        boolean valid() {
            return kind == null;
        }
    }

    /** Collects one router call: text, then exactly one terminal event. Never throws, so the upstream never cancels. */
    private static final class BufferingSink implements StreamSink {
        private final StringBuilder text = new StringBuilder();
        private Completion completion;
        private Throwable error;

        @Override
        public void onToken(String piece) {
            text.append(piece);
        }

        @Override
        public void onComplete(Completion c) {
            completion = c;
        }

        @Override
        public void onError(Throwable e) {
            error = e;
        }
    }
}
