package dev.raggateway.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.raggateway.validation.NetworkntOutputValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The t08 result-file contract (bench/summary.schema.json, D-45) is a valid JSON schema and accepts a minimal
 * well-formed summary. The document below is test data only: its numbers are not measurements and never go in
 * the README (only bench/summary/*.json written by bench/run-all.sh do).
 */
class BenchSummarySchemaTest {

    static final Path SCHEMA = Path.of("bench/summary.schema.json");
    final NetworkntOutputValidator validator = new NetworkntOutputValidator(new ObjectMapper());

    static final String MINIMAL = """
            {"run_id":"20000101T0000Z-test-data","measured_on":"test data, not a measurement","date":"2000-01-01",
             "git_commit":"0000000",
             "setup":{"jvm_xmx":"512m","virtual_threads":true,"mock_latency":"test","load_generator_shares_host":true,
                      "k6_image":"grafana/k6:2.3.0","price_date":"2000-01-01"},
             "scenarios":{
               "overhead":{"requests":10000,
                 "added_latency_seen_by_client_ms":{"ttfb":{"p50":1,"p95":2,"p99":3},"total":{"p50":1,"p95":2,"p99":3}},
                 "gateway_processing_overhead_ms":{"p50":1,"p95":2,"p99":3}},
               "cost_per_1k":{"label":"modelled from list prices on a synthetic workload","baseline_usd":1,"cache_usd":1,
                 "routing_usd":1,"cache_and_routing_usd":1,"requests_per_run":1}}}
            """;

    @Test
    void schemaIsAValidJsonSchemaAndAcceptsAMinimalSummary() throws Exception {
        String schema = Files.readString(SCHEMA);
        validator.checkSchema(schema);

        assertThat(validator.validate(schema, MINIMAL)).isEmpty();
    }

    @Test
    void schemaRejectsTheMistakesTheReadmeCheckReliesOnCatching() throws Exception {
        String schema = Files.readString(SCHEMA);

        // fewer than 10,000 overhead requests (AC-t08-5)
        assertThat(validator.validate(schema, MINIMAL.replace("\"requests\":10000", "\"requests\":9999"))).isNotEmpty();
        // a cost row without the "modelled" label (AC-t08-7)
        assertThat(validator.validate(schema, MINIMAL.replace("modelled from list prices on a synthetic workload", "measured")))
                .isNotEmpty();
        // no hardware label (C14)
        assertThat(validator.validate(schema, MINIMAL.replace("\"measured_on\":\"test data, not a measurement\",", "")))
                .isNotEmpty();
    }
}
