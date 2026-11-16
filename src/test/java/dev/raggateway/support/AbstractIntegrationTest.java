package dev.raggateway.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need the whole application on the <b>shared</b> containers: real HTTP port,
 * real Postgres (pgvector) and Redis from {@link SharedContainers}, Flyway migrations applied. State is wiped
 * before each test.
 *
 * <p>A test that needs its own Redis (e.g. to stop it) must extend {@link AbstractAppTest} instead: the Redis
 * registration below would win over the subclass's (D-35).
 *
 * <p>Use {@code @MockitoBean} or a {@code @TestConfiguration} sparingly: each distinct set of overrides
 * creates a new application context. Prefer WireMock for providers and {@link FakeLlmProvider} only where
 * a test controls failures token by token.
 */
public abstract class AbstractIntegrationTest extends AbstractAppTest {

    @DynamicPropertySource
    static void sharedRedisProperties(DynamicPropertyRegistry registry) {
        var rd = SharedContainers.redis();
        registry.add("spring.data.redis.host", rd::getHost);
        registry.add("spring.data.redis.port", () -> rd.getMappedPort(6379));
        // Containers start cold; give Redis more than the production 50 ms in tests that do not test timeouts.
        registry.add("spring.data.redis.timeout", () -> "2s");
    }
}
