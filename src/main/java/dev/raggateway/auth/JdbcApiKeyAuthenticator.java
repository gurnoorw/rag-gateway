package dev.raggateway.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.core.Tenant;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link ApiKeyAuthenticator} backed by the {@code api_keys} and {@code tenants} tables (D-22) with a
 * Caffeine cache in front (D-23): key hash to principal, fixed TTL after write, bounded size. Unknown and
 * revoked keys are not cached, so a key created a moment ago works on its first request and a flood of
 * random keys cannot fill the cache. A revoked key keeps working for up to the TTL on each instance.
 *
 * <p>The presented key is hashed immediately and never stored, logged or put in an exception message
 * (AC-t01-8); the cache key is the hash.
 */
public final class JdbcApiKeyAuthenticator implements ApiKeyAuthenticator {

    /** D-23 defaults. */
    public static final Duration DEFAULT_TTL = Duration.ofSeconds(30);
    public static final long DEFAULT_MAX_ENTRIES = 10_000;

    private static final String LOOKUP = """
            SELECT k.id AS key_id, k.groups, k.is_admin,
                   t.id AS tenant_id, t.name, t.tokens_per_minute, t.monthly_budget_usd, t.allowed_models
            FROM api_keys k
            JOIN tenants t ON t.id = k.tenant_id
            WHERE k.key_hash = :hash AND k.revoked_at IS NULL
            """;

    private final JdbcClient jdbc;
    private final Cache<String, ApiKeyPrincipal> cache;

    public JdbcApiKeyAuthenticator(JdbcClient jdbc) {
        this(jdbc, DEFAULT_TTL, DEFAULT_MAX_ENTRIES, Ticker.systemTicker());
    }

    /** Test seam: a custom ticker drives the TTL without sleeping. */
    public JdbcApiKeyAuthenticator(JdbcClient jdbc, Duration ttl, long maxEntries, Ticker ticker) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxEntries)
                .ticker(ticker)
                .build();
    }

    @Override
    public Optional<ApiKeyPrincipal> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) return Optional.empty();
        String hash = ApiKeyHasher.sha256Hex(presentedKey);
        // A null result from the loader is not stored, so unknown keys are looked up again next time.
        return Optional.ofNullable(cache.get(hash, this::load));
    }

    /** Number of cached principals (tests). */
    long cachedEntries() {
        cache.cleanUp();
        return cache.estimatedSize();
    }

    private ApiKeyPrincipal load(String hash) {
        List<ApiKeyPrincipal> rows = jdbc.sql(LOOKUP).param("hash", hash).query(JdbcApiKeyAuthenticator::map).list();
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static ApiKeyPrincipal map(ResultSet rs, int rowNum) throws SQLException {
        var tenant = new Tenant(rs.getString("tenant_id"), rs.getString("name"), rs.getLong("tokens_per_minute"),
                rs.getBigDecimal("monthly_budget_usd"), textArray(rs.getArray("allowed_models")));
        return new ApiKeyPrincipal(rs.getString("key_id"), tenant, textArray(rs.getArray("groups")),
                rs.getBoolean("is_admin"));
    }

    private static Set<String> textArray(Array array) throws SQLException {
        if (array == null) return Set.of();
        try {
            return Set.copyOf(Arrays.asList((String[]) array.getArray()));
        } finally {
            array.free();
        }
    }
}
