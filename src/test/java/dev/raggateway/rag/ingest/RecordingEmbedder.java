package dev.raggateway.rag.ingest;

import dev.raggateway.rag.Embedder;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The fake embedder behind a recorder, for tests that build their own {@link IndexingWorker} on the real stores: it
 * records every call's inputs, can be made to fail, and can park a call until the test opens its {@link Gate} (one
 * gate per call, in call order), so two workers can be stopped at chosen points without sleeps.
 */
final class RecordingEmbedder implements Embedder {

    private final FakeEmbedder delegate = new FakeEmbedder();
    final List<List<String>> calls = new CopyOnWriteArrayList<>();
    volatile Predicate<List<String>> failWhen = inputs -> false;
    private final ConcurrentLinkedQueue<Gate> gates = new ConcurrentLinkedQueue<>();

    /** A parked embedder call: {@link #entered} once it is inside; it returns (or fails) after {@link #open}. */
    static final class Gate {
        final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean fail;

        void open() {
            release.countDown();
        }

        void openAndFail() {
            fail = true;
            release.countDown();
        }

        boolean awaitEntered() throws InterruptedException {
            return entered.await(10, TimeUnit.SECONDS);
        }
    }

    /** The next call that has no gate yet parks on this one. */
    Gate gateNextCall() {
        Gate g = new Gate();
        gates.add(g);
        return g;
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @Override
    public List<float[]> embedDocuments(List<String> texts) {
        calls.add(List.copyOf(texts));
        Gate gate = gates.poll();
        if (gate != null) {
            gate.entered.countDown();
            try {
                if (!gate.release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("test never opened the gate");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (gate.fail) throw new OllamaEmbedder.EmbeddingException("Ollama embed returned HTTP 500 (gated)");
        }
        if (failWhen.test(texts)) throw new OllamaEmbedder.EmbeddingException("Ollama embed returned HTTP 500");
        return delegate.embedDocuments(texts);
    }

    @Override
    public float[] embedQuery(String text) {
        return delegate.embedQuery(text);
    }

    /** Titles of the documents embedded (each input is {@code <title>\n\n<chunk>}), once per call. */
    List<String> titlesPerCall() {
        return calls.stream().map(c -> c.getFirst().substring(0, c.getFirst().indexOf("\n\n"))).toList();
    }

    Set<String> titles() {
        return titlesPerCall().stream().collect(Collectors.toSet());
    }

    void clear() {
        calls.clear();
    }
}
