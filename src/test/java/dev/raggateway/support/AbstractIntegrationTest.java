package dev.raggateway.support;

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
 * Base class for tests that need the whole application: real HTTP port, real Postgres (pgvector) and
 * Redis from {@link SharedContainers}, Flyway migrations applied. State is wiped before each test.
 *
 * <p>Use {@code @MockitoBean} or a {@code @TestConfiguration} sparingly: each distinct set of overrides
 * creates a new application context. Prefer WireMock for providers and {@link FakeLlmProvider} only where
 * a test controls failures token by token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability(tracing = false) // metrics export on, so /actuator/prometheus exists (t04)
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected StringRedisTemplate redis;

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        var pg = SharedContainers.postgres();
        var rd = SharedContainers.redis();
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.data.redis.host", rd::getHost);
        registry.add("spring.data.redis.port", () -> rd.getMappedPort(6379));
        // Containers start cold; give Redis more than the production 50 ms in tests that do not test timeouts.
        registry.add("spring.data.redis.timeout", () -> "2s");
    }

    @BeforeEach
    void resetBeforeEach() {
        resetState();
    }

    /** Truncate every application table (keeps the Flyway history) and flush Redis. */
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
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
