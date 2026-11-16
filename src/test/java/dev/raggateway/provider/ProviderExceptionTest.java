package dev.raggateway.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Which failures may move to the next provider (D-30, AC-t03-6). */
class ProviderExceptionTest {

    static ProviderException http(int status) {
        boolean retryable = status == 429 || status >= 500;
        return new ProviderException(ProviderException.Kind.HTTP_STATUS, "x", retryable, status, null, null);
    }

    @Test
    void retryableFailuresAndConfigurationFaultsAllowFallbackOtherClientErrorsDoNot() {
        assertThat(http(500).fallbackAllowed()).isTrue();
        assertThat(http(429).fallbackAllowed()).isTrue();
        assertThat(http(401).fallbackAllowed()).isTrue();
        assertThat(http(403).fallbackAllowed()).isTrue();
        assertThat(http(401).retryable()).isFalse();
        assertThat(http(400).fallbackAllowed()).isFalse();
        assertThat(http(404).fallbackAllowed()).isTrue();
        assertThat(http(404).configurationFault()).isTrue();
        assertThat(http(422).fallbackAllowed()).isFalse();
        assertThat(new ProviderException(ProviderException.Kind.FIRST_TOKEN_TIMEOUT, "t", true, 0, null, null)
                .fallbackAllowed()).isTrue();
    }
}
