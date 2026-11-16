package dev.raggateway.provider.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.Timeout;
import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ProviderProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Claude SDK client and adapter beans (D-04), created when {@code gateway.providers.claude.base-url} is set.
 * The client is built explicitly from {@code gateway.providers.claude} (never {@code fromEnv()}, so tests
 * cannot pick up a real key or base URL from the environment):
 * <ul>
 *   <li>{@code baseUrl}: the Anthropic API, or WireMock in tests;</li>
 *   <li>{@code timeout}: connect from {@code connect-timeout}, read (longest silence on the socket) from
 *       {@code idle-timeout}; the per-event watchdogs come in t03;</li>
 *   <li>{@code maxRetries(0)}: the fallback router owns retries (D-07);</li>
 *   <li>the key goes in {@code X-Api-Key}, the Messages API's header for API keys. SDK 3.3.0 sends any static
 *       credential, including one given to {@code apiKey(..)}, as {@code Authorization: Bearer} unless the
 *       request already carries {@code X-Api-Key} or {@code Authorization} ({@code StaticAuthHeaders}); the
 *       explicit header turns that off. {@code apiKey(..)} is still set so the SDK never falls back to
 *       credentials from the environment or a profile file.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.providers.claude", name = "base-url")
public class ClaudeClientConfig {

    /** Placeholder sent when no key is configured, so the app starts and an upstream 401 says why. */
    static final String NO_KEY = "unset";
    /** Header the Messages API reads an API key from. */
    static final String API_KEY_HEADER = "X-Api-Key";

    @Bean(destroyMethod = "close")
    AnthropicClient claudeClient(GatewayProperties properties) {
        return client(properties.providers().get(ClaudeProvider.NAME));
    }

    @Bean
    ClaudeProvider claudeProvider(AnthropicClient claudeClient, GatewayProperties properties) {
        return new ClaudeProvider(claudeClient, properties);
    }

    /** Builds the SDK client from provider properties. Also used by the adapter tests. */
    public static AnthropicClient client(ProviderProperties p) {
        String key = p.apiKey() == null || p.apiKey().isBlank() ? NO_KEY : p.apiKey();
        Duration connect = p.connectTimeout() != null ? p.connectTimeout() : Duration.ofSeconds(1);
        Duration read = p.idleTimeout() != null ? p.idleTimeout() : Duration.ofSeconds(30);
        return AnthropicOkHttpClient.builder()
                .baseUrl(p.baseUrl())
                .apiKey(key)
                .putHeader(API_KEY_HEADER, key)
                .timeout(Timeout.builder().connect(connect).read(read).build())
                .maxRetries(0)
                .build();
    }
}
