package dev.raggateway.rag.query;

import dev.raggateway.rag.RetrievalMode;
import dev.raggateway.rag.RetrievalQuery;
import dev.raggateway.rag.RetrievalResult;
import dev.raggateway.rag.RetrievedChunk;
import dev.raggateway.rag.Retriever;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link Retriever} the test programs: returns the chunks it was given (at most {@code topK}), records every query,
 * and can be told to wait, degrade to keyword mode or fail. Stands in for the hybrid retriever until both halves of
 * t10 are merged; {@code RagQueryE2EIT} then runs the same checks on the real one.
 */
public class FakeRetriever implements Retriever {

    private final List<RetrievalQuery> queries = new CopyOnWriteArrayList<>();
    private volatile List<RetrievedChunk> chunks = List.of();
    private volatile Duration delay = Duration.ZERO;
    private volatile boolean degrade;
    private volatile RuntimeException failure;

    @Override
    public RetrievalResult retrieve(RetrievalQuery query) {
        queries.add(query);
        if (!delay.isZero()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) throw failure;
        List<RetrievedChunk> result = chunks.subList(0, Math.min(query.topK(), chunks.size()));
        RetrievalMode mode = degrade && query.mode() == RetrievalMode.HYBRID ? RetrievalMode.KEYWORD : query.mode();
        return new RetrievalResult(result, mode);
    }

    public FakeRetriever returning(List<RetrievedChunk> chunks) {
        this.chunks = List.copyOf(chunks);
        return this;
    }

    public FakeRetriever delayed(Duration delay) {
        this.delay = delay;
        return this;
    }

    public FakeRetriever degradingHybridToKeyword() {
        this.degrade = true;
        return this;
    }

    public FakeRetriever failingWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    public List<RetrievalQuery> queries() {
        return List.copyOf(queries);
    }

    public void reset() {
        queries.clear();
        chunks = List.of();
        delay = Duration.ZERO;
        degrade = false;
        failure = null;
    }

    /** {@code n} chunks of {@code doc-1..n}, ids {@code chunk-1..n}, in rank order. */
    public static List<RetrievedChunk> chunks(int n) {
        List<RetrievedChunk> list = new java.util.ArrayList<>();
        for (int i = 1; i <= n; i++) {
            list.add(new RetrievedChunk("chunk-" + i, "doc-" + i, "Title " + i, 0,
                    "Fact number " + i + " about pods.", 2.0 / (60 + i), i, i));
        }
        return list;
    }

    /** Puts a {@link FakeRetriever} in the application context, ahead of any other {@link Retriever}. */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        FakeRetriever fakeRetriever() {
            return new FakeRetriever();
        }
    }
}
