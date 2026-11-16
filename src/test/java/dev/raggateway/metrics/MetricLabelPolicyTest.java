package dev.raggateway.metrics;

import dev.raggateway.support.PrometheusScrape;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Each rule of the AC-t04-3 label policy fires on a scrape that breaks it, so {@code MetricLabelPolicyIT} can fail. */
class MetricLabelPolicyTest {

    static final String REQUEST_ID = "3f1c2a9e-1111-4a2b-9c3d-0123456789ab";
    static final String API_KEY = "rgw_test_policy_key_0123456789abcdef";

    final MetricLabelPolicy policy = new MetricLabelPolicy(Set.of("claude-haiku-5-5", "llama3.2:1b"),
            Set.of("claude", "ollama"), Set.of("acme"), Set.of(REQUEST_ID, API_KEY, "free text prompt"));

    static PrometheusScrape scrape(String... lines) {
        return PrometheusScrape.parse(String.join("\n", lines) + "\n");
    }

    @Test
    void boundedLabelsPass() {
        var ok = scrape(
                "gateway_requests_total{code=\"none\",model=\"claude-haiku-5-5\",provider=\"claude\",status_class=\"2xx\",tenant=\"acme\"} 5.0",
                "gateway_requests_total{code=\"invalid_request\",model=\"other\",provider=\"none\",status_class=\"4xx\",tenant=\"acme\"} 1.0",
                "gateway_request_duration_seconds_bucket{model=\"llama3.2:1b\",provider=\"ollama\",le=\"0.025\"} 1.0",
                "gateway_request_duration_seconds_bucket{model=\"llama3.2:1b\",provider=\"ollama\",le=\"+Inf\"} 1.0",
                "gateway_tokens_total{direction=\"in\",model=\"claude-haiku-5-5\",tenant=\"acme\"} 3.0",
                "gateway_provider_fallbacks_total{from_provider=\"claude\",to_provider=\"ollama\"} 1.0",
                "gateway_ratelimit_failopen_total{component=\"limiter\"} 0.0",
                "gateway_listener_failures_total{listener=\"RequestLogWriter\"} 0.0",
                "gateway_cache_lookups_total{result=\"hit\"} 1.0",
                // Outside the scope: Resilience4j and Spring's own series.
                "resilience4j_circuitbreaker_state{name=\"claude\",state=\"closed\"} 1.0",
                "http_server_requests_seconds_count{uri=\"/v1/chat/completions\"} 1.0");

        assertThat(policy.violations(ok)).isEmpty();
    }

    @Test
    void aLabelNameOutsideTheAllowListFails() {
        assertThat(policy.violations(scrape("gateway_requests_total{request_id=\"" + REQUEST_ID + "\"} 1.0")))
                .anySatisfy(v -> assertThat(v).contains("label 'request_id' is not allowed"));
        assertThat(policy.violations(scrape("rag_retrieval_duration_seconds{quantile=\"0.99\"} 1.0")))
                .anySatisfy(v -> assertThat(v).contains("label 'quantile' is not allowed"));
    }

    @Test
    void aRawModelStringFails() {
        assertThat(policy.violations(scrape(
                "gateway_requests_total{model=\"gpt-client-chose-this\",tenant=\"acme\"} 1.0")))
                .anySatisfy(v -> assertThat(v).contains("model=\"gpt-client-chose-this\" is not in the bounded set"));
    }

    @Test
    void anUnknownTenantProviderStatusOrCodeFails() {
        assertThat(policy.violations(scrape(
                "gateway_requests_total{code=\"teapot\",provider=\"openai\",status_class=\"418\",tenant=\"someone-else\"} 1.0")))
                .hasSize(4);
    }

    @Test
    void requestDataInAnyValueFails() {
        assertThat(policy.violations(scrape("gateway_cache_lookups_total{result=\"" + API_KEY + "\"} 1.0")))
                .anySatisfy(v -> assertThat(v).contains("carries request data"));
        assertThat(policy.violations(scrape("gateway_routing_decisions_total{rule=\"free text prompt\"} 1.0")))
                .anySatisfy(v -> assertThat(v).contains("carries request data"))
                .anySatisfy(v -> assertThat(v).contains("is not in the bounded set"));
    }

    @Test
    void outOfScopeSeriesAreIgnored() {
        assertThat(policy.violations(scrape("jvm_memory_used_bytes{id=\"" + REQUEST_ID + "\"} 1.0"))).isEmpty();
    }
}
