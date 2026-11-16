package dev.raggateway.support;

import dev.raggateway.cache.CachedResponse;
import dev.raggateway.cache.ResponseCache;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Usage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The t05 cache fake keeps the contract the filter relies on (ResponseCache javadoc, D-40). */
class InMemoryResponseCacheTest {

    static final CachedResponse ANSWER = new CachedResponse("hi", FinishReason.STOP, "claude-haiku-5-5", "claude",
            new Usage(3, 1));

    @Test
    void entriesAndLocksExpireLocksAreExclusiveAndOnlyTheOwnerUnlocks() {
        var now = new AtomicLong();
        var cache = new InMemoryResponseCache(now::get);

        cache.put("k", ANSWER, Duration.ofSeconds(10));
        assertThat(cache.get("k")).contains(ANSWER);
        String first = cache.tryLock("k", Duration.ofSeconds(1)).orElseThrow();
        assertThat(cache.tryLock("k", Duration.ofSeconds(1))).isEmpty();
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        String second = cache.tryLock("k", Duration.ofSeconds(1)).orElseThrow(); // the first one expired
        cache.unlock("k", first);                                                 // stale holder: no effect
        assertThat(cache.tryLock("k", Duration.ofSeconds(1))).isEmpty();
        cache.unlock("k", second);
        assertThat(cache.tryLock("k", Duration.ofSeconds(1))).isPresent();
        now.addAndGet(Duration.ofSeconds(9).toNanos());
        assertThat(cache.get("k")).isEmpty();
        assertThat(cache.lockGrants()).isEqualTo(3);
    }

    @Test
    void failingCacheFailsOpen() {
        var cache = new InMemoryResponseCache().failing(true);

        cache.put("k", ANSWER, Duration.ofSeconds(10));
        assertThat(cache.get("k")).isEmpty();
        assertThat(cache.tryLock("k", Duration.ofSeconds(1))).as("go ahead, do not wait").contains(ResponseCache.FAILED_OPEN);
        assertThat(cache.tryLock("k", Duration.ofSeconds(1))).contains(ResponseCache.FAILED_OPEN);
        cache.unlock("k", ResponseCache.FAILED_OPEN);
        assertThat(cache.puts()).isZero();
    }
}
