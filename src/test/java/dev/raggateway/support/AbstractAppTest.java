package dev.raggateway.support;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

/**
 * Base class for full-application tests that bring their <b>own</b> Redis: real HTTP port, shared Postgres
 * (pgvector) with Flyway migrations, state wiped before each test. It deliberately registers no Redis
 * properties: Spring applies a superclass's {@code @DynamicPropertySource} after a subclass's, so a base
 * class that registered the shared Redis would silently override a subclass's own one (D-35). A subclass
 * must register {@code spring.data.redis.host} and {@code spring.data.redis.port} itself; if it forgets,
 * {@code application-test.yml} points Redis at an {@code .invalid} host and the test fails loudly.
 *
 * <p>Most tests want the shared Redis: extend {@link AbstractIntegrationTest} instead.
 *
 * <p>Use {@code @MockitoBean} or a {@code @TestConfiguration} sparingly: each distinct set of overrides
 * creates a new application context. Prefer WireMock for providers and {@link FakeLlmProvider} only where
 * a test controls failures token by token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability(tracing = false) // metrics export on, so /actuator/prometheus exists (t04)
@ActiveProfiles("test")
public abstract class AbstractAppTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    /** Provider circuit breakers (t03); shared by every test of the same context, so reset before each test. */
    @Autowired
    protected CircuitBreakerRegistry circuitBreakers;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        var pg = SharedContainers.postgres();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
    }

    @BeforeEach
    void resetBeforeEach() {
        resetState();
    }

    /**
     * Truncate every application table (keeps the Flyway history), flush Redis and close every provider circuit
     * breaker, so failures provoked by an earlier test cannot open a breaker under the next one.
     */
    protected void resetState() {
        List<String> tables = jdbc.sql("""
                SELECT tablename FROM pg_tables
                WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'
                """).query(String.class).list();
        if (!tables.isEmpty()) {
            jdbc.sql("TRUNCATE " + String.join(", ", tables) + " RESTART IDENTITY CASCADE").update();
        }
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
