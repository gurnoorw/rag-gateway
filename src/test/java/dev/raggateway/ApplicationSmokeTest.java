package dev.raggateway;

import dev.raggateway.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scaffold smoke test: the application context starts against real Postgres (pgvector) and Redis from
 * Testcontainers, migrations run, virtual threads are on, and the actuator answers.
 */
class ApplicationSmokeTest extends AbstractIntegrationTest {

    @Value("${spring.threads.virtual.enabled}")
    boolean virtualThreads;

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @Test
    void contextStartsAndHealthIsUp() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void pgvectorIsAtLeast080() {
        String version = jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
                .query(String.class).single();

        String[] parts = version.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        assertThat(major > 0 || minor >= 8).as("pgvector %s >= 0.8.0", version).isTrue();
    }

    @Test
    void redisAnswers() {
        redis.opsForValue().set("smoke", "1");
        assertThat(redis.opsForValue().get("smoke")).isEqualTo("1");
    }

    @Test
    void virtualThreadsAreEnabled() {
        assertThat(virtualThreads).isTrue();
    }

    @Test
    void prometheusEndpointIsExposed() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("jvm_memory_used_bytes");
    }
}
