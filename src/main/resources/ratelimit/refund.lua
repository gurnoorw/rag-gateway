-- Return unused tokens to a bucket: refill to now, add ARGV[3], cap at capacity. Same clock, fields and
-- TTL rule as acquire.lua. A missing bucket is a full one (never used, or idle past its TTL), so nothing
-- is written.
--
-- KEYS[1] = rl:{<bucketKey>}
-- ARGV[1] = capacity           ARGV[2] = refill per minute        ARGV[3] = refunded tokens
-- returns 1 if the bucket was updated, 0 if it did not exist
local capacity = tonumber(ARGV[1])
local refill_per_minute = tonumber(ARGV[2])
local refunded = tonumber(ARGV[3])

local b = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
if not b[1] then
  return 0
end

local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000000 + tonumber(t[2])
local tokens = tonumber(b[1])
local ts = tonumber(b[2]) or now

local elapsed = math.max(0, now - ts)
tokens = math.min(capacity, tokens + elapsed * refill_per_minute / 60000000 + refunded)

redis.call('HSET', KEYS[1], 'tokens', string.format('%.17g', tokens), 'ts', string.format('%.0f', math.max(now, ts)))
local ttl_ms = math.max(1000, math.ceil(capacity * 60000 / refill_per_minute) * 2)
redis.call('PEXPIRE', KEYS[1], string.format('%.0f', ttl_ms))
return 1
