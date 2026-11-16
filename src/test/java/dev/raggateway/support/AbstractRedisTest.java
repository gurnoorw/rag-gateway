package dev.raggateway.support;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Base class for Redis tests without a Spring context (limiter Lua script, cache store). Gives a
 * {@link StringRedisTemplate} on the shared container and flushes it before each test.
 * {@link #newConnectionFactory()} creates an extra, independent connection, e.g. to simulate a second
 * gateway instance (AC-t02-2).
 */
public abstract class AbstractRedisTest {

    protected static LettuceConnectionFactory connectionFactory;
    protected static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connectionFactory = newConnectionFactory();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @BeforeEach
    void flush() {
        redis.execute((RedisCallback<Object>) c -> {
            c.serverCommands().flushAll();
            return null;
        });
    }

    /** A new, started connection factory to the shared Redis. The caller destroys it. */
    protected static LettuceConnectionFactory newConnectionFactory() {
        var rd = SharedContainers.redis();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(rd.getHost(), rd.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }
}
