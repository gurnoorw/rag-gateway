-- Token bucket: refill, then take ARGV[3] tokens if available. Atomic: Redis runs a script without
-- interleaving other commands, so concurrent gateways cannot both spend the same tokens (C5).
-- The clock is Redis TIME, never the caller's, so every gateway instance uses one clock (C24).
--
-- KEYS[1] = rl:{<bucketKey>}   hash fields: tokens (may be fractional), ts (microseconds, Redis TIME)
-- ARGV[1] = capacity           ARGV[2] = refill per minute        ARGV[3] = requested tokens
-- returns {allowed (1/0), retry_after_ms (0 when allowed, >= 1 when denied)}
local capacity = tonumber(ARGV[1])
local refill_per_minute = tonumber(ARGV[2])
local requested = tonumber(ARGV[3])

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])

local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(b[1]) or capacity
local ts = tonumber(b[2]) or now

local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * refill_per_minute / 60000000)

local allowed, wait = 0, 0
if tokens >= requested then
  tokens = tokens - requested
  allowed = 1
else
  wait = math.max(1, math.ceil((requested - tokens) * 60000 / refill_per_minute))
end

-- Explicit formats: Lua's default number-to-string keeps 14 digits, too few for a microsecond timestamp.
-- ts never moves backwards, so a Redis clock step back does not re-grant elapsed time.
redis.call('HSET', KEYS[1], 'tokens', string.format('%.17g', tokens), 'ts', string.format('%.0f', math.max(now, ts)))
-- An idle bucket is full again after capacity / rate; after twice that it is dropped (missing = full).
local ttl_ms = math.max(1000, math.ceil(capacity * 60000 / refill_per_minute) * 2)
redis.call('PEXPIRE', KEYS[1], string.format('%.0f', ttl_ms))
return {allowed, wait}
