package dev.raggateway.support;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the V2/V3 migrations apply and the fixtures write what tests will rely on. */
class TestDataTest extends AbstractPostgresTest {

    @Test
    void insertsTenantAndHashedKeyWithGroups() {
        String tenant = "fixture-" + UUID.randomUUID();
        TestData.insertTenant(jdbc, tenant, 10_000, new BigDecimal("25.00"), Set.of("claude-haiku-5-5"));
        UUID keyId = TestData.insertKey(jdbc, tenant, "test-key-not-secret", Set.of("eng", "sre"), true);

        String hash = jdbc.sql("SELECT key_hash FROM api_keys WHERE id = :id").param("id", keyId)
                .query(String.class).single();
        List<String> groups = jdbc.sql("SELECT unnest(groups) FROM api_keys WHERE id = :id ORDER BY 1")
                .param("id", keyId).query(String.class).list();
        List<String> models = jdbc.sql("SELECT unnest(allowed_models) FROM tenants WHERE id = :id")
                .param("id", tenant).query(String.class).list();

        assertThat(hash).isEqualTo(TestData.sha256Hex("test-key-not-secret")).hasSize(64)
                .doesNotContain("test-key-not-secret");
        assertThat(groups).containsExactly("eng", "sre");
        assertThat(models).containsExactly("claude-haiku-5-5");
    }

    @Test
    void requestLogTableHasNoTextColumnsForPromptOrResponse() {
        List<String> columns = jdbc.sql("""
                SELECT column_name FROM information_schema.columns WHERE table_name = 'request_log'
                """).query(String.class).list();

        assertThat(columns).contains("request_id", "tenant_id", "cost_usd", "status", "cache_status")
                .noneMatch(c -> c.contains("prompt") || c.contains("content") || c.contains("response"));
    }
}
