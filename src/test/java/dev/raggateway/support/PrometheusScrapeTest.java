package dev.raggateway.support;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The scrape parser reads the format Micrometer writes (t04 fixture). */
class PrometheusScrapeTest {

    static final String SAMPLE = """
            # HELP gateway_requests_total Requests
            # TYPE gateway_requests_total counter
            gateway_requests_total{code="none",model="claude-haiku-5-5",provider="claude",status_class="2xx",tenant="acme"} 5.0
            gateway_requests_total{code="rate_limited",model="claude-haiku-5-5",provider="none",status_class="4xx",tenant="acme"} 2.0
            # TYPE gateway_request_duration_seconds histogram
            gateway_request_duration_seconds_bucket{model="m",provider="claude",le="0.5"} 3.0
            gateway_request_duration_seconds_bucket{model="m",provider="claude",le="+Inf"} 5.0
            gateway_request_duration_seconds_count{model="m",provider="claude"} 5.0
            weird_total{msg="say \\"hi\\" \\\\ ok"} 1.0
            """;

    @Test
    void parsesValuesLabelsAndFamilies() {
        var scrape = PrometheusScrape.parse(SAMPLE);

        assertThat(scrape.sum("gateway_requests_total", Map.of("tenant", "acme"))).isEqualTo(7.0);
        assertThat(scrape.sum("gateway_requests_total", Map.of("status_class", "2xx"))).isEqualTo(5.0);
        assertThat(scrape.series("gateway_request_duration_seconds_bucket")).hasSize(2);
        assertThat(scrape.series("gateway_request_duration_seconds_bucket").get(1).label("le")).isEqualTo("+Inf");
        assertThat(PrometheusScrape.parse("g{a=\"b\"} +Inf\n").samples().get(0).value()).isInfinite();
        assertThat(scrape.hasFamily(PrometheusScrape.prometheusBase("gateway.request.duration"))).isTrue();
        assertThat(scrape.hasFamily("gateway_requests")).isTrue();
        assertThat(scrape.hasFamily("gateway_cache_lookups")).isFalse();
        assertThat(scrape.labelNames(n -> n.startsWith("gateway_")))
                .containsExactly("code", "le", "model", "provider", "status_class", "tenant");
        assertThat(scrape.series("weird_total").get(0).label("msg")).isEqualTo("say \"hi\" \\ ok");
        assertThat(scrape.series("weird_total").get(0).value()).isCloseTo(1.0, within(1e-9));
    }
}
