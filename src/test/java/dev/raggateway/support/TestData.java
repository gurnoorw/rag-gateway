package dev.raggateway.support;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * Test fixtures for tenants and API keys (t01 round prep). Writes rows directly with SQL so tests do
 * not depend on the production key store. Keys are stored as SHA-256 hex, as in production (D-22);
 * test keys are obviously fake strings chosen by the test.
 */
public final class TestData {

    private TestData() {
    }

    /** Insert a tenant. An empty {@code allowedModels} means every catalog model is allowed. */
    public static void insertTenant(JdbcClient jdbc, String id, long tokensPerMinute, BigDecimal budgetUsd,
                                    Set<String> allowedModels) {
        jdbc.sql("""
                        INSERT INTO tenants (id, name, tokens_per_minute, monthly_budget_usd, allowed_models)
                        VALUES (:id, :name, :tpm, :budget, CAST(:models AS text[]))
                        """)
                .param("id", id)
                .param("name", "Tenant " + id)
                .param("tpm", tokensPerMinute)
                .param("budget", budgetUsd)
                .param("models", allowedModels.toArray(String[]::new))
                .update();
    }

    /**
     * Insert an API key for a tenant, stored as the SHA-256 hex of {@code plaintextKey}.
     *
     * @return the key's id (not the key)
     */
    public static UUID insertKey(JdbcClient jdbc, String tenantId, String plaintextKey, Set<String> groups,
                                 boolean admin) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO api_keys (id, tenant_id, key_hash, name, groups, is_admin)
                        VALUES (:id, :tenant, :hash, :name, CAST(:groups AS text[]), :admin)
                        """)
                .param("id", id)
                .param("tenant", tenantId)
                .param("hash", sha256Hex(plaintextKey))
                .param("name", "test key " + id)
                .param("groups", groups.toArray(String[]::new))
                .param("admin", admin)
                .update();
        return id;
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes; same function the key store must use. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
