package dev.raggateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Load shedding at the edge (D-46): the request over the limit gets a fast 503 overloaded; admitted ones finish. */
class InFlightLimitFilterTest {

    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final ObjectMapper json = new ObjectMapper();

    static MockHttpServletRequest v1() {
        return new MockHttpServletRequest("POST", "/v1/chat/completions");
    }

    @Test
    void theRequestOverTheLimitIs503OverloadedWithRetryAfterAndTheOthersComplete() throws Exception {
        var filter = new InFlightLimitFilter(2, registry, json);
        var release = new CountDownLatch(1);
        FilterChain held = (req, res) -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ((MockHttpServletResponse) res).setStatus(200);
        };

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<MockHttpServletResponse> a = pool.submit(() -> run(filter, held));
            Future<MockHttpServletResponse> b = pool.submit(() -> run(filter, held));
            await().atMost(java.time.Duration.ofSeconds(5)).until(() -> filter.inFlight() == 2);

            MockHttpServletResponse third = run(filter, (req, res) -> { throw new AssertionError("must not run"); });
            assertThat(third.getStatus()).isEqualTo(503);
            assertThat(third.getHeader("Retry-After")).isEqualTo("1");
            assertThat(json.readTree(third.getContentAsString()).at("/error/code").asText()).isEqualTo("overloaded");

            release.countDown();
            assertThat(a.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
            assertThat(b.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        }
        assertThat(filter.inFlight()).as("permits are returned").isZero();
        assertThat(registry.find(MetricNames.REQUESTS_SHED).counter().count()).isEqualTo(1.0);
        assertThat(run(filter, (req, res) -> ((MockHttpServletResponse) res).setStatus(200)).getStatus()).isEqualTo(200);
    }

    @Test
    void zeroDisablesTheLimitAndNonV1PathsAreNotCounted() throws Exception {
        var off = new InFlightLimitFilter(0, registry, json);
        assertThat(run(off, (req, res) -> ((MockHttpServletResponse) res).setStatus(204)).getStatus()).isEqualTo(204);

        var one = new InFlightLimitFilter(1, registry, json);
        var actuator = new MockHttpServletRequest("GET", "/actuator/health");
        var res = new MockHttpServletResponse();
        one.doFilter(actuator, res, (rq, rs) -> assertThat(one.inFlight()).isZero());
    }

    private static MockHttpServletResponse run(InFlightLimitFilter filter, FilterChain chain) throws Exception {
        var res = new MockHttpServletResponse();
        filter.doFilter(v1(), res, chain);
        return res;
    }
}
