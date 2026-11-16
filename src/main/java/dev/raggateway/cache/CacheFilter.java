package dev.raggateway.cache;

import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.CacheStatus;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Usage;
import dev.raggateway.error.GatewayException;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.metrics.MetricsConfig;
import dev.raggateway.pipeline.FilterChain;
import dev.raggateway.pipeline.FilterOrder;
import dev.raggateway.pipeline.ForwardingSink;
import dev.raggateway.pipeline.GatewayFilter;
import dev.raggateway.pipeline.StreamSink;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Exact-match response cache (t05; LLD "Response cache"; DECISIONS.md C3, C21, D-05, D-17, D-39, D-40). Runs after
 * model selection, so the key holds the resolved model, and before the rate limiter, so a hit spends no tokens and
 * never touches the bucket or the budget.
 *
 * <ol>
 *   <li><b>Eligibility.</b> Caching disabled, or {@code temperature} absent or not exactly 0 →
 *       {@link CacheStatus#NOT_ELIGIBLE} (D-05: {@code temperature: 0} is the caller's opt-in to a stored answer,
 *       not a determinism guarantee). Otherwise {@code X-Gateway-Cache-Bypass: true} → {@link CacheStatus#BYPASS}:
 *       no read, no write. Both go straight to {@code chain.next}. The status is set on every path; the client
 *       sinks put it in {@code X-Gateway-Cache} and the log row.</li>
 *   <li><b>Hit.</b> The stored content as one token, then {@code onComplete} with {@link Usage#ZERO}; no
 *       {@code chain.next}, so no attempt, no cost, and the log row and metrics name the provider {@code cache}.</li>
 *   <li><b>Miss, single-flight.</b> {@code tryLock}. The holder (a token, {@link ResponseCache#FAILED_OPEN}
 *       included) reads once more (not after {@code FAILED_OPEN}), then calls {@code chain.next} through a recording wrapper and unlocks with its own
 *       token in {@code finally}. A request that did not get the lock polls every {@code pollInterval}: a stored
 *       answer is a hit; a free lock means the previous fill failed or was not cacheable, so it reads once more and
 *       otherwise calls through <b>without keeping the lock</b> (single-flight covers the first fill only; after a
 *       failed one the waiters go in parallel instead of one after another, D-41); after {@code lockTtl} it calls
 *       through without the lock. The time spent waiting is a deliberate wait
 *       ({@link RequestContext#addWaitNanos}), not gateway overhead (D-39).</li>
 *   <li><b>Store</b> only when the stream reached {@code onComplete} and the client sink accepted it, with finish
 *       reason {@code stop} or {@code length} (a {@code content_filter} refusal may depend on policy that changes),
 *       and the serving attempt {@code SUCCEEDED} on the selected model and is not a fallback (C21). Never on an error,
 *       a client disconnect, a partial stream or invalid output (t07 never completes those).</li>
 * </ol>
 *
 * <p><b>When the store happens.</b> After {@code chain.next} returns, not inside the wrapper's {@code onComplete}:
 * the client's stream is already closed by then (the wrapper forwards {@code onComplete} first), and the terminal
 * records the serving attempt only after the adapter returns, so inside {@code onComplete} the context still shows
 * the previous attempt (for a fallback answer, the primary's failure with {@code fallback = false}). The holder stores
 * before it unlocks, so a waiter that sees the lock free also sees the answer.
 *
 * <p><b>Redis down</b> (D-17): the store reads as a miss, drops the put and returns {@code FAILED_OPEN} from
 * {@code tryLock}, so the request goes ahead at once and never waits for a fill that cannot come. The store counts
 * those errors ({@code gateway.cache.errors}); this filter counts one {@code gateway.cache.lookups{result}} per
 * request.
 *
 * <p>A {@code FAILED_OPEN} holder still stores a qualifying answer: a {@code tryLock} that timed out on the client
 * may still have taken the lock on Redis (with a token nobody holds, until {@code lockTtl}), and the waiters behind
 * that lock can then only be served by the stored answer.
 */
@Component
@Order(FilterOrder.CACHE)
public class CacheFilter implements GatewayFilter {

    private static final Logger log = LoggerFactory.getLogger(CacheFilter.class);

    private final ResponseCache cache;
    private final CacheKeyStrategy keys;
    private final CacheProperties properties;
    private final MeterRegistry registry;
    private final LongSupplier nanoClock;

    @Autowired
    public CacheFilter(ResponseCache cache, CacheKeyStrategy keys, CacheProperties properties, MeterRegistry registry) {
        this(cache, keys, properties, registry, System::nanoTime);
    }

    CacheFilter(ResponseCache cache, CacheKeyStrategy keys, CacheProperties properties, MeterRegistry registry,
                LongSupplier nanoClock) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    @Override
    public void handle(RequestContext ctx, StreamSink sink, FilterChain chain) throws GatewayException {
        ChatRequest request = ctx.request();
        if (!properties.enabled() || !temperatureZero(request)) {
            decided(ctx, CacheStatus.NOT_ELIGIBLE);
            chain.next(ctx, sink);
            return;
        }
        if (ctx.cacheBypassRequested()) {
            decided(ctx, CacheStatus.BYPASS);
            chain.next(ctx, sink);
            return;
        }

        String key = keys.keyFor(ctx.tenant().id(), request);
        Optional<CachedResponse> stored = cache.get(key);
        if (stored.isPresent()) {
            replay(ctx, sink, stored.get());
            return;
        }
        Optional<String> token = cache.tryLock(key, properties.lockTtl());
        if (token.isPresent()) {
            fillHoldingLock(ctx, sink, chain, key, token.get());
        } else {
            waitForFill(ctx, sink, chain, key);
        }
    }

    /** Exactly 0; absent means "provider default", which is not the caller's opt-in (D-05). */
    private static boolean temperatureZero(ChatRequest request) {
        return request.temperature() != null && request.temperature() == 0.0;
    }

    /** Another request holds the lock: poll for its answer or a free lock, at most {@code lockTtl}. */
    private void waitForFill(RequestContext ctx, StreamSink sink, FilterChain chain, String key) {
        long start = nanoClock.getAsLong();
        long deadline = start + properties.lockTtl().toNanos();
        long poll = properties.pollInterval().toNanos();
        while (true) {
            long remaining = deadline - nanoClock.getAsLong();
            if (remaining <= 0 || !pause(Math.min(poll, remaining))) break;
            Optional<CachedResponse> stored = cache.get(key);
            if (stored.isPresent()) {
                ctx.addWaitNanos(nanoClock.getAsLong() - start);
                replay(ctx, sink, stored.get());
                return;
            }
            Optional<String> token = cache.tryLock(key, properties.lockTtl());
            if (token.isPresent()) {
                // The lock came free without an answer: the previous fill failed or was not cacheable (an outage
                // answered by the fallback, C21). Read once more, then release at once and call through without
                // the lock, so the other waiters do not queue behind each other one fill at a time (D-41).
                ctx.addWaitNanos(nanoClock.getAsLong() - start);
                Optional<CachedResponse> late = ResponseCache.FAILED_OPEN.equals(token.get())
                        ? Optional.empty() : cache.get(key);
                cache.unlock(key, token.get());
                if (late.isPresent()) replay(ctx, sink, late.get());
                else fill(ctx, sink, chain, key);
                return;
            }
        }
        // The holder is slower than the lock TTL (or this thread was interrupted): call the provider ourselves.
        ctx.addWaitNanos(nanoClock.getAsLong() - start);
        log.debug("request {}: cache fill not seen within {}, calling through", ctx.requestId(), properties.lockTtl());
        fill(ctx, sink, chain, key);
    }

    /** @return false when interrupted (the flag is restored and the caller stops waiting) */
    private static boolean pause(long nanos) {
        try {
            Thread.sleep(Duration.ofNanos(nanos));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void fillHoldingLock(RequestContext ctx, StreamSink sink, FilterChain chain, String key, String token) {
        try {
            // The previous holder may have stored and unlocked between our read and our lock: read once more, or
            // every request that raced it would call the provider again. Pointless when Redis is failing.
            if (!ResponseCache.FAILED_OPEN.equals(token)) {
                Optional<CachedResponse> stored = cache.get(key);
                if (stored.isPresent()) {
                    replay(ctx, sink, stored.get());
                    return;
                }
            }
            fill(ctx, sink, chain, key);
        } finally {
            cache.unlock(key, token);
        }
    }

    /** A miss: call the rest of the chain and store the answer if it qualifies. */
    private void fill(RequestContext ctx, StreamSink sink, FilterChain chain, String key) {
        decided(ctx, CacheStatus.MISS);
        String selectedModel = ctx.request().model();
        var recorder = new RecordingSink(sink);
        chain.next(ctx, recorder);
        storeIfCacheable(ctx, key, selectedModel, recorder);
    }

    private void storeIfCacheable(RequestContext ctx, String key, String selectedModel, RecordingSink recorder) {
        Completion completion = recorder.completion;
        if (completion == null || recorder.failed) return;
        if (completion.finishReason() != FinishReason.STOP && completion.finishReason() != FinishReason.LENGTH) return;
        if (ctx.fallbackUsed()) return;
        Optional<Attempt> serving = ctx.servingAttempt();
        if (serving.isEmpty()) return;
        Attempt attempt = serving.get();
        if (attempt.outcome() != AttemptOutcome.SUCCEEDED || attempt.fallback()
                || !attempt.model().equals(selectedModel)) {
            return;
        }
        cache.put(key, new CachedResponse(recorder.content.toString(), completion.finishReason(), completion.model(),
                attempt.provider(), completion.usage()), properties.ttl());
    }

    private void replay(RequestContext ctx, StreamSink sink, CachedResponse stored) {
        decided(ctx, CacheStatus.HIT);
        if (!stored.content().isEmpty()) sink.onToken(stored.content());
        sink.onComplete(new Completion(stored.finishReason(), Usage.ZERO, stored.model()));
    }

    /** Sets the status and counts the lookup; called exactly once per request. */
    private void decided(RequestContext ctx, CacheStatus status) {
        ctx.setCacheStatus(status);
        Counter.builder(MetricNames.CACHE_LOOKUPS)
                .description(MetricsConfig.CACHE_LOOKUPS_DESCRIPTION)
                .tag("result", resultLabel(status))
                .register(registry)
                .increment();
    }

    /** {@code result} label value: {@code hit}, {@code miss}, {@code bypass} or {@code not_eligible}. */
    public static String resultLabel(CacheStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }

    /**
     * Buffers the text and remembers the terminal event. {@code onComplete} is forwarded first and recorded only if
     * the client sink accepted it: a disconnect thrown there leaves {@code completion} null, so nothing is stored.
     */
    private static final class RecordingSink extends ForwardingSink {
        private final StringBuilder content = new StringBuilder();
        private Completion completion;
        private boolean failed;

        RecordingSink(StreamSink delegate) {
            super(delegate);
        }

        @Override
        public void onToken(String text) {
            super.onToken(text);
            content.append(text);
        }

        @Override
        public void onComplete(Completion c) {
            super.onComplete(c);
            completion = c;
        }

        @Override
        public void onError(Throwable error) {
            failed = true;
            super.onError(error);
        }
    }
}
