package dev.raggateway.resilience;

import dev.raggateway.pipeline.Terminal;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.resilience.FallbackProperties.ChainEntry;
import dev.raggateway.support.AbstractIntegrationTest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The t03 beans in the real application context: properties from application.yml, router, breaker metrics. */
class ResilienceConfigIT extends AbstractIntegrationTest {

    @Autowired FallbackProperties properties;
    @Autowired Terminal terminal;
    @Autowired List<LlmProvider> providers;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void fallbackSettingsComeFromApplicationYml() {
        assertThat(terminal).isInstanceOf(FallbackRouter.class);
        assertThat(properties.chain()).containsExactly(new ChainEntry("ollama", "llama3.2:1b"));
        assertThat(properties.maxAttempts()).isEqualTo(2);
        assertThat(properties.backoffBase()).isEqualTo(Duration.ofMillis(200));
        assertThat(properties.backoffCap()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.retryAfterCap()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.breaker().window()).isEqualTo(20);
        assertThat(properties.breaker().failureRateThreshold()).isEqualTo(50f);
        assertThat(properties.breaker().openDuration()).isEqualTo(Duration.ofSeconds(30));
        var config = circuitBreakers.getDefaultConfig();
        assertThat(config.getSlidingWindowSize()).isEqualTo(20);
        assertThat(config.getMinimumNumberOfCalls()).isEqualTo(20);
        assertThat(config.getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(1);
    }

    /** AC-t03-10 (bean part): breaker state per provider is on the Prometheus endpoint from startup. */
    @Test
    void ac_t03_10_breakerStatePerProviderIsOnThePrometheusEndpoint() throws Exception {
        assertThat(scrape()).contains("resilience4j_circuitbreaker_state{")
                .containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"claude\"[^}]*state=\"closed\"[^}]*} 1\\.0");
        for (LlmProvider p : providers) {
            assertThat(scrape()).contains("name=\"" + p.name() + "\"");
        }

        circuitBreakers.circuitBreaker("claude").transitionToOpenState();

        assertThat(scrape())
                .containsPattern("resilience4j_circuitbreaker_state\\{[^}]*name=\"claude\"[^}]*state=\"open\"[^}]*} 1\\.0");
        assertThat(circuitBreakers.circuitBreaker("claude").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    private String scrape() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
