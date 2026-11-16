package dev.raggateway.ratelimit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link RateLimiter} shared by all gateway instances: one hash per bucket in Redis, read, refilled and
 * debited by one Lua script ({@code ratelimit/acquire.lua}) so the check-and-take is atomic (C5), with
 * Redis {@code TIME} as the only clock (C24). Refunds use {@code ratelimit/refund.lua}.
 *
 * <p>Key: {@code rl:{<bucketKey>}} (D-33). The braces are a Redis Cluster hash tag, so the key's slot is
 * fixed by the bucket key alone.
 *
 * <p>Differs from the LLD: no app-server timestamp is passed (C24), and the script receives the refill rate
 * per minute rather than per millisecond, so no fractional rate crosses the wire. Errors (Redis down, the
 * 50 ms command timeout, D-17) propagate as Spring {@code DataAccessException}s; {@link FailOpenRateLimiter}
 * decides what to do with them.
 */
@Component
public final class RedisTokenBucketLimiter implements RateLimiter {

    static final String KEY_PREFIX = "rl:";

    private static final RedisScript<List<Long>> ACQUIRE = listScript("ratelimit/acquire.lua");
    private static final RedisScript<Long> REFUND = RedisScript.of(new ClassPathResource("ratelimit/refund.lua"), Long.class);

    private final StringRedisTemplate redis;

    public RedisTokenBucketLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** The Redis key of a bucket: {@code rl:{<bucketKey>}}. */
    public static String redisKey(String bucketKey) {
        return KEY_PREFIX + "{" + bucketKey + "}";
    }

    @Override
    public Decision tryAcquire(String bucketKey, long tokens, BucketSpec spec) {
        TokenBucket.checkRequested(tokens, spec); // before Redis: a request that can never fit is the caller's 400
        List<Long> result = redis.execute(ACQUIRE, List.of(redisKey(bucketKey)),
                Long.toString(spec.capacity()), Long.toString(spec.refillPerMinute()), Long.toString(tokens));
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("unexpected reply from acquire.lua: " + result);
        }
        return result.get(0) == 1L ? Decision.allow() : Decision.deny(result.get(1));
    }

    @Override
    public void refund(String bucketKey, long tokens, BucketSpec spec) {
        if (tokens < 0) throw new IllegalArgumentException("refund must be >= 0");
        if (tokens == 0) return;
        redis.execute(REFUND, List.of(redisKey(bucketKey)),
                Long.toString(spec.capacity()), Long.toString(spec.refillPerMinute()), Long.toString(tokens));
    }

    // The script returns a two-element integer array; Spring Data only takes the raw List class here.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RedisScript<List<Long>> listScript(String path) {
        var script = new DefaultRedisScript<List<Long>>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(path)));
        script.setResultType((Class) List.class);
        return script;
    }
}
