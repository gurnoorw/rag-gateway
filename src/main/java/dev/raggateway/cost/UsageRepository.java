package dev.raggateway.cost;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Sums over {@code request_log} for one tenant and time range (t02, {@code GET /v1/usage}). One aggregate
 * query on the {@code (tenant_id, created_at)} index; every row counts as a request, including rejected ones
 * (D-33). The tenant id is always a bind parameter taken from the authenticated key, never from the client.
 */
@Repository
public class UsageRepository {

    private static final String SUM = """
            SELECT count(*)                         AS requests,
                   coalesce(sum(input_tokens), 0)  AS input_tokens,
                   coalesce(sum(output_tokens), 0) AS output_tokens,
                   coalesce(sum(cost_usd), 0)      AS cost_usd
            FROM request_log
            WHERE tenant_id = :tenantId AND created_at >= :from AND created_at < :to
            """;

    /** Totals of a range; {@code costUsd} is the exact sum of the rows' 6-decimal costs. */
    public record Totals(long requests, long inputTokens, long outputTokens, BigDecimal costUsd) {
    }

    private final JdbcClient jdbc;

    public UsageRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Totals for rows with {@code from <= created_at < to}. */
    public Totals totals(String tenantId, Instant from, Instant to) {
        return jdbc.sql(SUM)
                .param("tenantId", tenantId)
                .param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
                .param("to", OffsetDateTime.ofInstant(to, ZoneOffset.UTC))
                .query((rs, n) -> new Totals(rs.getLong("requests"), rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"), rs.getBigDecimal("cost_usd")))
                .single();
    }
}
