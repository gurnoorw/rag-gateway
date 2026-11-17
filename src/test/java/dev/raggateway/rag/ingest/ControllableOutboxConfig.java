package dev.raggateway.rag.ingest;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test-only wiring for {@link DocumentControllerIT} and {@link DocumentServiceTxIT}: the real outbox store behind a
 * wrapper that can hold or fail {@link OutboxStore#insert}, which runs inside the PUT's transaction after the
 * document row is written. Both classes import this and set {@code gateway.rag.max-concurrent-writes=1}, so they
 * share one cached application context. Pass-through unless a test arms it; tests disarm it after each run.
 */
@TestConfiguration(proxyBeanMethods = false)
class ControllableOutboxConfig {

    @Bean
    @Primary
    ControllableOutboxStore controllableOutboxStore(JdbcOutboxStore real) {
        return new ControllableOutboxStore(real);
    }

    static final class ControllableOutboxStore implements OutboxStore {

        private final OutboxStore delegate;
        private volatile CountDownLatch entered;
        private volatile CountDownLatch release;
        private volatile RuntimeException failBefore;
        private volatile RuntimeException failAfter;

        ControllableOutboxStore(OutboxStore delegate) {
            this.delegate = delegate;
        }

        /** The next insert waits (inside the transaction) until {@link #release()}; returns "it is waiting". */
        CountDownLatch holdNextInsert() {
            entered = new CountDownLatch(1);
            release = new CountDownLatch(1);
            return entered;
        }

        void release() {
            CountDownLatch r = release;
            if (r != null) r.countDown();
        }

        /** The next insert throws before writing the outbox row. */
        void failNextInsertBeforeWriting(RuntimeException e) {
            failBefore = e;
        }

        /** The next insert writes the outbox row, then throws: both rows exist inside the transaction. */
        void failNextInsertAfterWriting(RuntimeException e) {
            failAfter = e;
        }

        void reset() {
            release();
            entered = null;
            release = null;
            failBefore = null;
            failAfter = null;
        }

        @Override
        public UUID insert(UUID documentId, String contentHash) {
            CountDownLatch e = entered;
            CountDownLatch r = release;
            if (e != null && r != null) {
                entered = null;
                e.countDown();
                try {
                    if (!r.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("test never released the hold");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            RuntimeException before = failBefore;
            if (before != null) {
                failBefore = null;
                throw before;
            }
            UUID id = delegate.insert(documentId, contentHash);
            RuntimeException after = failAfter;
            if (after != null) {
                failAfter = null;
                throw after;
            }
            return id;
        }

        @Override
        public List<OutboxEvent> claimBatch(int max, Duration lease) {
            return delegate.claimBatch(max, lease);
        }

        @Override
        public boolean markDone(UUID id, int attempt) {
            return delegate.markDone(id, attempt);
        }

        @Override
        public boolean release(UUID id, int attempt, String error, Duration retryAfter) {
            return delegate.release(id, attempt, error, retryAfter);
        }

        @Override
        public DeadLetter markDead(UUID id, int attempt, String error) {
            return delegate.markDead(id, attempt, error);
        }

        @Override
        public long countUnresolvedDead() {
            return delegate.countUnresolvedDead();
        }
    }
}
