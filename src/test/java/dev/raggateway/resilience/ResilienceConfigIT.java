package dev.raggateway.resilience;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.CharRatioTokenEstimator;
import dev.raggateway.core.ChatRequest;
import dev.raggateway.core.Completion;
import dev.raggateway.core.FinishReason;
import dev.raggateway.core.Message;
import dev.raggateway.core.RequestContext;
import dev.raggateway.core.Role;
import dev.raggateway.core.Tenant;
import dev.raggateway.core.Usage;
import dev.raggateway.metrics.MetricNames;
import dev.raggateway.pipeline.StreamSink;
import dev.raggateway.pipeline.Terminal;
import dev.raggateway.provider.LlmProvider;
import dev.raggateway.resilience.FallbackProperties.ChainEntry;
import dev.raggateway.support.AbstractIntegrationTest;
import dev.raggateway.support.FakeLlmProvider;
import dev.raggateway.validation.ValidatingTerminal;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The t03 beans in the real application context: properties from application.yml, router, breaker metrics; and the
 * t07 wiring around the router (validating terminal in front, structural check around every adapter).
 */
class ResilienceConfigIT extends AbstractIntegrationTest {

    @Autowired FallbackProperties properties;
    @Autowired Terminal terminal;
    @Autowired FallbackRouter router;
    @Autowired List<LlmProvider> providers;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void fallbackSettingsComeFromApplicationYml() {
        // t07 (D-43): the pipeline's terminal is the validating decorator; the router is its own bean behind it.
        assertThat(terminal).isInstanceOf(ValidatingTerminal.class);
        assertThat(router).isNotNull();
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

    /** t07 (D-43): the router wraps every adapter, so an empty stop answer is a retryable failure of that attempt. */
    @Test
    void routerBeanWrapsEveryAdapterInTheStructuralCheck() {
        var opus = "claude-opus-5-5";
        var fake = new FakeLlmProvider("claude", Set.of(opus))
                .enqueue(FakeLlmProvider.script(new FakeLlmProvider.Complete(
                        new Completion(FinishReason.STOP, new Usage(5, 0), opus))))
                .enqueue(FakeLlmProvider.reply("fine"));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("fake", fake);
        var meters = new SimpleMeterRegistry();
        var props = FallbackProperties.withChain(List.of());
        var gateway = new GatewayProperties(1024, 8192, Map.of(opus,
                new GatewayProperties.ModelProperties("claude", opus, BigDecimal.ONE, BigDecimal.ONE)), Map.of());
        var noWait = new FullJitterBackoff(Duration.ZERO, Duration.ZERO, Duration.ZERO);

        FallbackRouter built = new ResilienceConfig().fallbackRouter(props, gateway,
                beans.getBeanProvider(LlmProvider.class),
                CircuitBreakerRegistry.of(ResilienceConfig.breakerConfig(props.breaker(), Clock.systemUTC())),
                RetryRegistry.of(ResilienceConfig.retryConfig(props, noWait)), new CharRatioTokenEstimator(), meters);
        var request = new ChatRequest(opus, List.of(new Message(Role.USER, "hi")), 16, null, null, List.of(), false,
                false, null);
        var ctx = new RequestContext("r", System.nanoTime(), new ApiKeyPrincipal("k",
                new Tenant("t", "T", 1000, BigDecimal.ONE, Set.of()), Set.of(), false), request);
        List<String> tokens = new ArrayList<>();
        built.call(ctx, new StreamSink() {
            @Override
            public void onToken(String text) {
                tokens.add(text);
            }

            @Override
            public void onComplete(Completion completion) {
            }

            @Override
            public void onError(Throwable error) {
                tokens.add("error");
            }
        });

        assertThat(fake.calls()).isEqualTo(2);
        assertThat(tokens).containsExactly("fine");
        assertThat(ctx.attempts().get(0).errorKind()).isEqualTo("MALFORMED_RESPONSE");
        assertThat(meters.find(MetricNames.VALIDATION_FAILURES).tags("model", opus, "kind", "structural").counter()
                .count()).isEqualTo(1);
    }

    private String scrape() throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/actuator/prometheus")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
