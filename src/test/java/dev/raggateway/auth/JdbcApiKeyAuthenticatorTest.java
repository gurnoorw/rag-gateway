package dev.raggateway.auth;

import com.github.benmanes.caffeine.cache.Ticker;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.support.AbstractPostgresTest;
import dev.raggateway.support.TestData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcApiKeyAuthenticatorTest extends AbstractPostgresTest {

    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private JdbcApiKeyAuthenticator authenticator;
    private String tenant;

    @BeforeEach
    void setUp() {
        authenticator = new JdbcApiKeyAuthenticator(jdbc, Duration.ofSeconds(30), 10_000, ticker);
        tenant = "auth-" + UUID.randomUUID();
        TestData.insertTenant(jdbc, tenant, 50_000, new BigDecimal("12.50"), Set.of("claude-haiku-5-5"));
    }

    @Test
    void hashMatchesTheFixtureHash() {
        assertThat(ApiKeyHasher.sha256Hex("rgw_test-key-not-secret"))
                .isEqualTo(TestData.sha256Hex("rgw_test-key-not-secret"))
                .hasSize(64);
    }

    @Test
    void knownKeyResolvesToPrincipalWithTenantGroupsAndAdminFlag() {
        String key = "rgw_test-" + UUID.randomUUID();
        UUID keyId = TestData.insertKey(jdbc, tenant, key, Set.of("eng", "sre"), true);

        Optional<ApiKeyPrincipal> principal = authenticator.authenticate(key);

        assertThat(principal).hasValueSatisfying(p -> {
            assertThat(p.keyId()).isEqualTo(keyId.toString());
            assertThat(p.groups()).containsExactlyInAnyOrder("eng", "sre");
            assertThat(p.admin()).isTrue();
            assertThat(p.tenant().id()).isEqualTo(tenant);
            assertThat(p.tenant().name()).isEqualTo("Tenant " + tenant);
            assertThat(p.tenant().tokensPerMinute()).isEqualTo(50_000);
            assertThat(p.tenant().monthlyBudgetUsd()).isEqualByComparingTo("12.50");
            assertThat(p.tenant().allowedModels()).containsExactly("claude-haiku-5-5");
        });
    }

    @Test
    void unknownBlankAndNullKeysAreRejected() {
        assertThat(authenticator.authenticate("rgw_unknown-" + UUID.randomUUID())).isEmpty();
        assertThat(authenticator.authenticate("")).isEmpty();
        assertThat(authenticator.authenticate("   ")).isEmpty();
        assertThat(authenticator.authenticate(null)).isEmpty();
    }

    @Test
    void unknownKeyIsNotCachedSoAKeyCreatedLaterWorksAtOnce() {
        String key = "rgw_test-" + UUID.randomUUID();
        assertThat(authenticator.authenticate(key)).isEmpty();
        assertThat(authenticator.cachedEntries()).isZero();

        TestData.insertKey(jdbc, tenant, key, Set.of(), false);

        assertThat(authenticator.authenticate(key)).isPresent();
    }

    @Test
    void revokedKeyIsRejected() {
        String key = "rgw_test-" + UUID.randomUUID();
        UUID id = TestData.insertKey(jdbc, tenant, key, Set.of(), false);
        jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE id = :id").param("id", id).update();

        assertThat(authenticator.authenticate(key)).isEmpty();
    }

    @Test
    void cachedPrincipalIsServedWithoutTheDatabaseUntilTtlExpires() {
        String key = "rgw_test-" + UUID.randomUUID();
        UUID id = TestData.insertKey(jdbc, tenant, key, Set.of(), false);
        assertThat(authenticator.authenticate(key)).isPresent();

        // Revocation is not seen while the entry is cached (D-23: up to 30 s per instance) ...
        jdbc.sql("UPDATE api_keys SET revoked_at = now() WHERE id = :id").param("id", id).update();
        nanos.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(authenticator.authenticate(key)).isPresent();

        // ... and is applied once the TTL has passed.
        nanos.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(authenticator.authenticate(key)).isEmpty();
    }
}
