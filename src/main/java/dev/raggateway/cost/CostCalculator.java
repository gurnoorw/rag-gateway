package dev.raggateway.cost;

import dev.raggateway.core.Attempt;
import dev.raggateway.core.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Cost of a request in integer micro-USD (DECISIONS.md D-33): the sum over every attempt of
 * {@code input x price_in + output x price_out}, each attempt priced by its own model (a fallback or a
 * validation retry costs what that model costs). Prices are USD per million tokens, so
 * {@code tokens x price} is already micro-USD; the exact sum is rounded once, half-up, to a whole micro-USD.
 *
 * <p>This one number is what the rate-limit filter adds to the monthly budget and what the request-log row
 * stores ({@code numeric(12,6)} USD is whole micro-USD), so the log and the budget always agree. Rounding
 * once per request, not per attempt, keeps the error below 0.5 micro-USD per request.
 *
 * <p>An attempt on a model missing from the price table (not reachable while model selection admits only
 * catalog models) is priced at 0 and logged at ERROR, rather than failing settlement or the log row.
 */
@Component
public class CostCalculator {

    private static final Logger log = LoggerFactory.getLogger(CostCalculator.class);
    private static final BigDecimal MICROS_PER_USD = BigDecimal.valueOf(1_000_000);

    private final PriceTable prices;

    public CostCalculator(PriceTable prices) {
        this.prices = Objects.requireNonNull(prices, "prices");
    }

    /** Cost of all attempts in whole micro-USD; 0 for no attempts (cache hit, rejected request). */
    public long costMicroUsd(List<Attempt> attempts) {
        BigDecimal micros = BigDecimal.ZERO;
        for (Attempt a : attempts) micros = micros.add(exactMicroUsd(a.model(), a.usage()));
        return micros.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** {@link #costMicroUsd} as USD with 6 decimals: the {@code request_log.cost_usd} value. */
    public BigDecimal costUsd(List<Attempt> attempts) {
        return toUsd(costMicroUsd(attempts));
    }

    /** Whole micro-USD as USD with exactly 6 decimals. */
    public static BigDecimal toUsd(long microUsd) {
        return BigDecimal.valueOf(microUsd).movePointLeft(6).setScale(6, RoundingMode.UNNECESSARY);
    }

    /** Whole USD (or any decimal) as micro-USD, rounded half-up. */
    public static long toMicroUsd(BigDecimal usd) {
        return usd.multiply(MICROS_PER_USD).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Unrounded cost of one usage on one model, in micro-USD. */
    BigDecimal exactMicroUsd(String model, Usage usage) {
        PriceTable.Price price = prices.price(model).orElse(null);
        if (price == null) {
            log.error("no price for model '{}'; {} tokens priced at 0", model, usage.total());
            return BigDecimal.ZERO;
        }
        return price.inputUsdPerMtok().multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(price.outputUsdPerMtok().multiply(BigDecimal.valueOf(usage.outputTokens())));
    }
}
