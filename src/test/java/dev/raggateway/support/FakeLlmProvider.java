package dev.raggateway.support;

import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Usage;
import dev.raggateway.pipeline.ClientDisconnectedException;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.provider.ProviderException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scriptable in-memory {@link LlmProvider} for pipeline, router and filter tests. Each call consumes the
 * next queued {@link Script} (or repeats the default script). A script is a list of steps: emit a token,
 * sleep, wait on a latch, fail, complete. It honours the provider contract: on
 * {@link ClientDisconnectedException} from the sink it records the cancel and rethrows.
 *
 * <pre>{@code
 * var fake = new FakeLlmProvider("claude", Set.of("claude-opus-5-5"))
 *         .enqueue(FakeLlmProvider.failBeforeFirstToken(500))
 *         .enqueue(FakeLlmProvider.reply("Hel", "lo"));
 * }</pre>
 */
public final class FakeLlmProvider implements LlmProvider {

    /** One step of a scripted response. */
    public sealed interface Step permits Token, Sleep, Await, Fail, Complete {
    }

    public record Token(String text) implements Step {
    }

    public record Sleep(Duration duration) implements Step {
    }

    /** Block until the latch opens (e.g. to hold N calls in flight at once). */
    public record Await(CountDownLatch latch) implements Step {
    }

    public record Fail(ProviderException error) implements Step {
    }

    public record Complete(Completion completion) implements Step {
    }

    /** A scripted response. */
    public record Script(List<Step> steps) {
        public Script {
            steps = List.copyOf(steps);
        }
    }

    private final String name;
    private final Set<String> models;
    private final ConcurrentLinkedQueue<Script> queue = new ConcurrentLinkedQueue<>();
    private volatile Script defaultScript = reply("ok");
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger cancellations = new AtomicInteger();
    private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());

    public FakeLlmProvider(String name, Set<String> models) {
        this.name = name;
        this.models = Set.copyOf(models);
    }

    public FakeLlmProvider enqueue(Script script) {
        queue.add(script);
        return this;
    }

    public FakeLlmProvider defaultScript(Script script) {
        this.defaultScript = script;
        return this;
    }

    /** Number of {@link #stream} calls so far. */
    public int calls() {
        return calls.get();
    }

    /** Calls that ended because the client disconnected. */
    public int cancellations() {
        return cancellations.get();
    }

    /** Requests received, in order. */
    public List<ChatRequest> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        queue.clear();
        calls.set(0);
        cancellations.set(0);
        requests.clear();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean supports(String model) {
        return models.contains(model);
    }

    @Override
    public void stream(ChatRequest request, StreamSink sink) throws ProviderException {
        calls.incrementAndGet();
        requests.add(request);
        Script script = queue.poll();
        if (script == null) script = defaultScript;
        try {
            for (Step step : script.steps()) {
                switch (step) {
                    case Token t -> sink.onToken(t.text());
                    case Sleep s -> sleep(s.duration());
                    case Await a -> await(a.latch());
                    case Fail f -> throw f.error();
                    case Complete c -> sink.onComplete(c.completion());
                }
            }
        } catch (ClientDisconnectedException e) {
            cancellations.incrementAndGet();
            throw e;
        }
    }

    // ---- script helpers ----

    /** Tokens, then STOP with usage (10 in, one out-token per piece). */
    public static Script reply(String... tokens) {
        List<Step> steps = new ArrayList<>();
        for (String t : tokens) steps.add(new Token(t));
        steps.add(new Complete(new Completion(FinishReason.STOP, new Usage(10, tokens.length), "fake-model")));
        return new Script(steps);
    }

    /** Tokens with a pause before each one. */
    public static Script slowReply(Duration gap, String... tokens) {
        List<Step> steps = new ArrayList<>();
        for (String t : tokens) {
            steps.add(new Sleep(gap));
            steps.add(new Token(t));
        }
        steps.add(new Complete(new Completion(FinishReason.STOP, new Usage(10, tokens.length), "fake-model")));
        return new Script(steps);
    }

    /** Retryable HTTP failure before any token (5xx or 429), or non-retryable for other 4xx. */
    public static Script failBeforeFirstToken(int httpStatus) {
        boolean retryable = httpStatus == 429 || httpStatus >= 500;
        return new Script(List.of(new Fail(new ProviderException(ProviderException.Kind.HTTP_STATUS,
                "fake upstream " + httpStatus, retryable, httpStatus, null, null))));
    }

    /** Emits the tokens, then fails as a broken connection. */
    public static Script failAfterTokens(String... tokens) {
        List<Step> steps = new ArrayList<>();
        for (String t : tokens) steps.add(new Token(t));
        steps.add(new Fail(new ProviderException(ProviderException.Kind.CONNECTION,
                "fake connection reset", true, 0, null, null)));
        return new Script(steps);
    }

    /** A full custom script. */
    public static Script script(Step... steps) {
        return new Script(List.of(steps));
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("latch timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
