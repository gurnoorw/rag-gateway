package dev.raggateway.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CharRatioTokenEstimatorTest {

    private final CharRatioTokenEstimator estimator = new CharRatioTokenEstimator();

    @Test
    void emptyOrNullTextIsZero() {
        assertThat(estimator.estimate("")).isZero();
        assertThat(estimator.estimate(null)).isZero();
    }

    @Test
    void roundsUpCharsDividedByThree() {
        assertThat(estimator.estimate("a")).isEqualTo(1);
        assertThat(estimator.estimate("abc")).isEqualTo(1);
        assertThat(estimator.estimate("abcd")).isEqualTo(2);
        assertThat(estimator.estimate("x".repeat(300))).isEqualTo(100);
    }

    @Test
    void countsCodePointsNotUtf16Units() {
        // three emoji: six UTF-16 units, three code points
        assertThat(estimator.estimate("😀😀😀")).isEqualTo(1);
    }

    @Test
    void inputAddsPerMessageOverhead() {
        var request = new ChatRequest("m", List.of(
                new Message(Role.SYSTEM, "abcdef"),       // 2 + 4
                new Message(Role.USER, "abcdefg")),       // 3 + 4
                16, null, null, List.of(), false, false, null);

        assertThat(estimator.estimateInput(request)).isEqualTo(13);
    }

    @Test
    void overEstimatesTypicalEnglish() {
        String text = "The quick brown fox jumps over the lazy dog while the gateway counts tokens.";
        // English runs at roughly 4 characters per token; the estimate must not be below that
        assertThat(estimator.estimate(text)).isGreaterThanOrEqualTo(text.length() / 4);
    }

    @Test
    void ratioIsConfigurable() {
        assertThat(new CharRatioTokenEstimator(4.0).estimate("abcdefgh")).isEqualTo(2);
        assertThatThrownBy(() -> new CharRatioTokenEstimator(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
