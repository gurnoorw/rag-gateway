package dev.raggateway.provider.ollama;

import dev.raggateway.config.GatewayProperties;
import dev.raggateway.config.GatewayProperties.ProviderProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Ollama HTTP client and adapter beans (t03, D-04), created when {@code gateway.providers.ollama.base-url} is
 * set (the default is {@code http://localhost:11434}, env {@code GATEWAY_OLLAMA_BASE_URL}, D-32).
 * <ul>
 *   <li>JDK {@link HttpClient} forced to HTTP/1.1: Ollama serves plain HTTP/1.1 on localhost, and HTTP/1.1
 *       keeps one exchange per connection, so closing a response closes exactly that call.</li>
 *   <li>{@code connect-timeout} from {@code gateway.providers.ollama} (default 1 s). There is no socket read
 *       timeout on the JDK client: the adapter's {@link dev.raggateway.provider.StreamWatchdog} bounds the
 *       first token (30 s in config, for a cold model load on the laptop) and the idle gap.</li>
 *   <li>No proxy: the JDK client only uses one when told to, and Ollama is local.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "gateway.providers.ollama", name = "base-url")
public class OllamaClientConfig {

    @Bean(destroyMethod = "close")
    HttpClient ollamaHttpClient(GatewayProperties properties) {
        return client(properties.providers().get(OllamaProvider.NAME));
    }

    @Bean
    OllamaProvider ollamaProvider(HttpClient ollamaHttpClient, GatewayProperties properties) {
        return new OllamaProvider(ollamaHttpClient, properties);
    }

    /** Builds the HTTP client from provider properties. Also used by the adapter tests. */
    public static HttpClient client(ProviderProperties p) {
        Duration connect = p != null && p.connectTimeout() != null ? p.connectTimeout() : Duration.ofSeconds(1);
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connect)
                .build();
    }
}
