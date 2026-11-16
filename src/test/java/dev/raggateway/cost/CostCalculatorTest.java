package dev.raggateway.cost;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
import dev.raggateway.core.Attempt;
import dev.raggateway.core.AttemptOutcome;
import dev.raggateway.core.Usage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CostCalculatorTest {

    private static final GatewayProperties CATALOG = new GatewayProperties(1024, 8192, Map.of(
            "claude-opus-5-5", new ModelProperties("claude", "claude-opus-5-5", new BigDecimal("4.00"), new BigDecimal("20.00")),
            "claude-haiku-5-5", new ModelProperties("claude", "claude-haiku-5-5", new BigDecimal("0.10"), new BigDecimal("0.50")),
            "llama3.2:1b", new ModelProperties("ollama", "llama3.2:1b", BigDecimal.ZERO, BigDecimal.ZERO)), Map.of());

    private final CostCalculator costs = new CostCalculator(new PriceTable(CATALOG));

    static Attempt attempt(String model, int in, int out) {
        return new Attempt("p", model, false, AttemptOutcome.SUCCEEDED, new Usage(in, out), false, 0, -1, null);
    }

    @Test
    void ac_t02_10_tokensTimesPricePerMillionIsMicroUsd() {
        // 1,000 x $4/Mtok + 500 x $20/Mtok = 4,000 + 10,000 micro-USD
        assertThat(costs.costMicroUsd(List.of(attempt("claude-opus-5-5", 1_000, 500)))).isEqualTo(14_000);
        assertThat(costs.costUsd(List.of(attempt("claude-opus-5-5", 1_000, 500)))).isEqualByComparingTo("0.014000");
    }

    @Test
    void ac_t02_10_fractionalMicroUsdRoundsHalfUpOncePerRequest() {
        // haiku: 3 x 0.10 + 1 x 0.50 = 0.8 micro-USD -> 1
        assertThat(costs.costMicroUsd(List.of(attempt("claude-haiku-5-5", 3, 1)))).isEqualTo(1);
        // 1 x 0.10 + 0 = 0.1 -> 0 ; 5 x 0.10 = 0.5 -> 1 (half-up)
        assertThat(costs.costMicroUsd(List.of(attempt("claude-haiku-5-5", 1, 0)))).isZero();
        assertThat(costs.costMicroUsd(List.of(attempt("claude-haiku-5-5", 5, 0)))).isEqualTo(1);
        // rounding happens on the sum, not per attempt: 0.4 + 0.4 = 0.8 -> 1 (per-attempt rounding would give 0)
        assertThat(costs.costMicroUsd(List.of(attempt("claude-haiku-5-5", 4, 0), attempt("claude-haiku-5-5", 4, 0))))
                .isEqualTo(1);
    }

    @Test
    void eachAttemptIsPricedByItsOwnModel() {
        var attempts = List.of(attempt("claude-opus-5-5", 100, 10), attempt("claude-haiku-5-5", 100, 50));
        // 100 x 4 + 10 x 20 = 600 ; 100 x 0.10 + 50 x 0.50 = 35
        assertThat(costs.costMicroUsd(attempts)).isEqualTo(635);
    }

    @Test
    void noAttemptsAndFreeModelCostNothing() {
        assertThat(costs.costMicroUsd(List.of())).isZero();
        assertThat(costs.costMicroUsd(List.of(attempt("llama3.2:1b", 5_000, 5_000)))).isZero();
        assertThat(costs.costUsd(List.of()).toPlainString()).isEqualTo("0.000000");
    }

    @Test
    void unknownModelIsPricedAtZeroInsteadOfFailing() {
        assertThat(costs.costMicroUsd(List.of(attempt("not-in-catalog", 1_000, 1_000)))).isZero();
    }

    @Test
    void conversionsBetweenUsdAndMicroUsd() {
        assertThat(CostCalculator.toMicroUsd(new BigDecimal("12.50"))).isEqualTo(12_500_000);
        assertThat(CostCalculator.toUsd(1)).isEqualByComparingTo("0.000001");
        assertThat(CostCalculator.toUsd(12_500_000).toPlainString()).isEqualTo("12.500000");
    }

    @Test
    void catalogModelWithoutAPriceFailsAtStartup() {
        var broken = new GatewayProperties(1024, 8192, Map.of(
                "m", new ModelProperties("claude", "m", new BigDecimal("1"), null)), Map.of());
        assertThatThrownBy(() -> new PriceTable(broken)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gateway.models.m");
        var negative = new GatewayProperties(1024, 8192, Map.of(
                "m", new ModelProperties("claude", "m", new BigDecimal("-1"), BigDecimal.ONE)), Map.of());
        assertThatThrownBy(() -> new PriceTable(negative)).isInstanceOf(IllegalStateException.class);
    }
}
