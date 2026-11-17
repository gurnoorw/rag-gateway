package dev.raggateway.rag.ingest;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Backoff 30 s × 2^(attempts−1), capped at 10 min; dead after 5 attempts (D-51). */
class WorkerRetryPropertiesTest {

    @Test
    void d51Defaults() {
        var d = WorkerRetryProperties.defaults();
        assertThat(d.maxAttempts()).isEqualTo(5);
        assertThat(d.retryBase()).isEqualTo(Duration.ofSeconds(30));
        assertThat(d.retryMax()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void backoffDoublesFromTheBaseAndIsCapped() {
        var d = WorkerRetryProperties.defaults();
        assertThat(d.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(d.backoff(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(d.backoff(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(d.backoff(4)).isEqualTo(Duration.ofMinutes(4));
        assertThat(d.backoff(5)).isEqualTo(Duration.ofMinutes(8));
        assertThat(d.backoff(6)).isEqualTo(Duration.ofMinutes(10));
        assertThat(d.backoff(1_000)).isEqualTo(Duration.ofMinutes(10));   // no overflow
        // Retries before the fifth (dead) attempt: 30 s + 1 + 2 + 4 min = 7.5 min.
        assertThat(d.backoff(1).plus(d.backoff(2)).plus(d.backoff(3)).plus(d.backoff(4)))
                .isEqualTo(Duration.ofSeconds(450));
    }

    @Test
    void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> new WorkerRetryProperties(0, null, null)).hasMessageContaining("max-attempts");
        assertThatThrownBy(() -> new WorkerRetryProperties(5, Duration.ZERO, null)).hasMessageContaining("retry-base");
        assertThatThrownBy(() -> new WorkerRetryProperties(5, Duration.ofMinutes(1), Duration.ofSeconds(1)))
                .hasMessageContaining("retry-max");
        assertThatThrownBy(() -> WorkerRetryProperties.defaults().backoff(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
