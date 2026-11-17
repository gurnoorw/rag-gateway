package dev.raggateway.eval.judge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spend-cap arithmetic and the settings file (D-53). Prices other than the real file's are test data. */
class SpendCapAndSettingsTest {

    @Test
    void worstCaseIsInputEstimatePlusMaxTokensAtTheConfiguredPrices() {
        SpendCap cap = new SpendCap(JudgeStubs.settings("0.05"));
        // 1,000 input tokens at $1/M + 1,000 output tokens at $10/M
        assertThat(cap.worstCase(1000)).isEqualByComparingTo("0.011");
        assertThat(cap.cost(500, 400)).isEqualByComparingTo("0.0045");
    }

    @Test
    void aCallFitsOnlyWhenItsWorstCaseFitsWhatIsLeftExactBoundaryIncluded() {
        SpendCap cap = new SpendCap(new BigDecimal("0.022"), BigDecimal.ONE, BigDecimal.TEN, 1000);
        assertThat(cap.allows(1000)).isTrue();
        cap.charge(1000, 1000); // 0.011
        assertThat(cap.allows(1000)).isTrue(); // 0.011 + 0.011 == 0.022: exactly the cap
        cap.charge(0, 1);
        assertThat(cap.allows(1000)).isFalse();
        assertThat(cap.spentUsd()).isEqualByComparingTo("0.01101");
        assertThat(cap.remainingUsd()).isEqualByComparingTo("0.01099");
        cap.chargeWorstCase(1000);
        assertThat(cap.spentUsd()).isEqualByComparingTo("0.02201");
        assertThat(cap.remainingUsd()).isEqualByComparingTo("0");
    }

    @Test
    void loadsTheCommittedSettingsFile() throws Exception {
        JudgeSettings s = JudgeSettings.load(JudgeSettings.DEFAULT_FILE);
        assertThat(s.model()).isEqualTo("claude-haiku-5-5");
        assertThat(s.inputUsdPerMtok()).isEqualByComparingTo("0.10");
        assertThat(s.outputUsdPerMtok()).isEqualByComparingTo("0.50");
        assertThat(s.effort()).isEqualTo("low");
        assertThat(s.maxTokens()).isEqualTo(2048);
        assertThat(s.capUsd()).isEqualByComparingTo("0.50");
        assertThat(s.apiKeyEnv()).isEqualTo("EVAL_JUDGE_API_KEY");
        assertThat(s.priceSource()).isNotBlank();
    }

    @Test
    void theCapCanBeLoweredOnTheCommandLineButNotRaised() throws Exception {
        JudgeSettings s = JudgeSettings.load(JudgeSettings.DEFAULT_FILE);
        assertThat(s.withCap(new BigDecimal("0.10")).capUsd()).isEqualByComparingTo("0.10");
        assertThatThrownBy(() -> s.withCap(new BigDecimal("0.51"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> s.withCap(BigDecimal.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void everyKeyIsRequiredAndBadValuesAreRefused(@TempDir Path dir) throws Exception {
        String good = Files.readString(JudgeSettings.DEFAULT_FILE);
        for (String key : new String[] {"model", "price.input-usd-per-mtok", "price.output-usd-per-mtok", "price.source",
                "effort", "max-tokens", "cap-usd", "api-key-env"}) {
            Path f = dir.resolve(key + ".properties");
            Files.writeString(f, good.replaceAll("(?m)^" + java.util.regex.Pattern.quote(key) + "=.*$", ""));
            assertThatThrownBy(() -> JudgeSettings.load(f)).as(key).isInstanceOf(IllegalArgumentException.class);
        }
        Path f = dir.resolve("effort.properties");
        Files.writeString(f, good.replace("effort=low", "effort=max"));
        assertThatThrownBy(() -> JudgeSettings.load(f)).isInstanceOf(IllegalArgumentException.class);
    }
}
