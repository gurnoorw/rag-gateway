package dev.raggateway.provider;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ModelProperties;
import dev.raggateway.support.FakeLlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D-30 from t03: a catalog model whose provider has no adapter fails startup, naming the model. */
class ProviderCatalogCheckTest {

    private static final Map<String, ModelProperties> CATALOG = Map.of(
            "claude-opus-5-5", model("claude"),
            "claude-haiku-5-5", model("claude"),
            "llama3.2:1b", model("ollama"));

    private static ModelProperties model(String provider) {
        return new ModelProperties(provider, null, BigDecimal.ONE, BigDecimal.ONE);
    }

    private static GatewayProperties properties(Map<String, ModelProperties> models) {
        return new GatewayProperties(1024, 8192, models, Map.of());
    }

    @Test
    void passesWhenEveryCatalogModelHasAnAdapter() {
        assertThatCode(() -> ProviderCatalogCheck.verify(CATALOG, Set.of("claude", "ollama"))).doesNotThrowAnyException();
    }

    @Test
    void failsNamingEveryModelWhoseProviderHasNoAdapter() {
        assertThatThrownBy(() -> ProviderCatalogCheck.verify(CATALOG, Set.of("ollama")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'claude-haiku-5-5' (provider 'claude')")
                .hasMessageContaining("'claude-opus-5-5' (provider 'claude')")
                .hasMessageNotContaining("llama3.2:1b")
                .hasMessageContaining("configured adapters: [ollama]");
    }

    @Test
    void aModelWithoutAProviderFails() {
        assertThatThrownBy(() -> ProviderCatalogCheck.verify(Map.of("orphan", model(null)), Set.of("claude")))
                .hasMessageContaining("'orphan' (provider 'null')");
    }

    @Test
    void applicationContextFailsToStartWhenAnAdapterIsMissing() {
        new ApplicationContextRunner()
                .withBean(GatewayProperties.class, () -> properties(CATALOG))
                .withBean("claude", LlmProvider.class, () -> new FakeLlmProvider("claude", Set.of("claude-opus-5-5")))
                .withUserConfiguration(ProviderCatalogCheck.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("'llama3.2:1b' (provider 'ollama')");
                });
    }

    @Test
    void applicationContextStartsWhenEveryAdapterIsPresent() {
        new ApplicationContextRunner()
                .withBean(GatewayProperties.class, () -> properties(CATALOG))
                .withBean("claude", LlmProvider.class, () -> new FakeLlmProvider("claude", Set.of("claude-opus-5-5")))
                .withBean("ollama", LlmProvider.class, () -> new FakeLlmProvider("ollama", Set.of("llama3.2:1b")))
                .withUserConfiguration(ProviderCatalogCheck.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ProviderCatalogCheck.class));
    }
}
