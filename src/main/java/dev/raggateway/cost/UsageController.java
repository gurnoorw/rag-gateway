package dev.raggateway.cost;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.raggateway.core.ApiKeyPrincipal;
import dev.raggateway.error.ErrorCode;
import dev.raggateway.error.GatewayException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * {@code GET /v1/usage?from=YYYY-MM-DD&to=YYYY-MM-DD}: request count, token sums and cost of the caller's
 * tenant, summed from {@code request_log} (t02, DECISIONS.md D-33).
 *
 * <ul>
 *   <li>Dates are UTC calendar days; {@code from} is inclusive, {@code to} exclusive. Each one defaults
 *       independently: {@code from} to the first day of the current UTC month, {@code to} to the first day of
 *       the next month, so no parameters means "this month".</li>
 *   <li>Not a date, {@code to <= from}, or a range longer than 366 days → 400 {@code invalid_request}.</li>
 *   <li>Only the tenant of the authenticated key (the auth interceptor covers {@code /v1/**}); there is no
 *       tenant parameter, so a caller cannot read another tenant's usage.</li>
 *   <li>{@code requests} counts every logged request, including 429s and other rejections; {@code cost_usd}
 *       is a string with 6 decimals so clients do not round it through a float.</li>
 * </ul>
 *
 * <p>Usage comes from the request log, which is written after each response (C7): a request still streaming
 * is not counted yet, and a lost log row under-reports here while the Redis budget stays correct.
 */
@RestController
public class UsageController {

    static final int MAX_RANGE_DAYS = 366;

    /** Response body. */
    public record UsageResponse(@JsonProperty("tenant_id") String tenantId,
                                @JsonProperty("from") String from,
                                @JsonProperty("to") String to,
                                @JsonProperty("requests") long requests,
                                @JsonProperty("input_tokens") long inputTokens,
                                @JsonProperty("output_tokens") long outputTokens,
                                @JsonProperty("cost_usd") String costUsd) {
    }

    private final UsageRepository repository;
    private final Clock clock;

    @Autowired
    public UsageController(UsageRepository repository) {
        this(repository, Clock.systemUTC());
    }

    UsageController(UsageRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock").withZone(ZoneOffset.UTC);
    }

    @GetMapping("/v1/usage")
    public UsageResponse usage(ApiKeyPrincipal caller,
                               @RequestParam(name = "from", required = false) String fromParam,
                               @RequestParam(name = "to", required = false) String toParam) {
        LocalDate monthStart = LocalDate.now(clock).withDayOfMonth(1);
        LocalDate from = fromParam == null ? monthStart : parse("from", fromParam);
        LocalDate to = toParam == null ? monthStart.plusMonths(1) : parse("to", toParam);
        if (!to.isAfter(from)) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "'to' must be after 'from' ('to' is exclusive)");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_RANGE_DAYS) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST,
                    "date range is longer than " + MAX_RANGE_DAYS + " days");
        }

        String tenantId = caller.tenant().id();
        UsageRepository.Totals totals = repository.totals(tenantId,
                from.atStartOfDay(ZoneOffset.UTC).toInstant(), to.atStartOfDay(ZoneOffset.UTC).toInstant());
        return new UsageResponse(tenantId, from.toString(), to.toString(), totals.requests(), totals.inputTokens(),
                totals.outputTokens(), totals.costUsd().setScale(6, RoundingMode.HALF_UP).toPlainString());
    }

    private static LocalDate parse(String name, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new GatewayException(ErrorCode.INVALID_REQUEST, "'" + name + "' must be a date as YYYY-MM-DD");
        }
    }
}
