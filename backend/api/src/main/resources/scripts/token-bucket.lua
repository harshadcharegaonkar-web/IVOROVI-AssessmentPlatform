-- Atomic token bucket. KEYS[1] = bucket key.
-- ARGV[1] capacity, ARGV[2] refill tokens per millisecond, ARGV[3] cost (0 = check only, consume nothing).
-- Returns {allowed (1/0), remaining tokens (floor), retry-after ms}. Uses the server clock so all API replicas agree.
local cap = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil then tokens = cap; ts = now end
tokens = math.min(cap, tokens + math.max(0, now - ts) * rate)
local needed = math.max(cost, 1)
local allowed = 0
local retry = 0
if tokens >= needed then
  allowed = 1
  tokens = tokens - cost
else
  retry = math.ceil((needed - tokens) / rate)
end
redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
redis.call('PEXPIRE', KEYS[1], math.ceil(cap / rate) + 1000)
return {allowed, math.floor(tokens), retry}
