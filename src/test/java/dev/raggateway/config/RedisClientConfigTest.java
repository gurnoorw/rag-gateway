package dev.raggateway.config;

import io.lettuce.core.ClientOptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Boot's Lettuce factory picks up the D-17 options; no Redis connection is opened. */
class RedisClientConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
            .withUserConfiguration(RedisClientConfig.class)
            .withPropertyValues("spring.data.redis.timeout=50ms", "spring.data.redis.connect-timeout=500ms");

    @Test
    void lettuceRejectsCommandsWhileDisconnectedAndKeepsBootsTimeouts() {
        runner.run(ctx -> {
            var client = ctx.getBean(LettuceConnectionFactory.class).getClientConfiguration();
            ClientOptions options = client.getClientOptions().orElseThrow();

            assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
            assertThat(options.isAutoReconnect()).isTrue();
            assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
            assertThat(options.getTimeoutOptions().isTimeoutCommands()).isTrue();
            assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofMillis(50));
        });
    }
}
