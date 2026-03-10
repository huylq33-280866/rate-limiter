-- ============================================================
-- LeakingBucket Rate Limiting Algorithm - Lua Script
-- ============================================================
--
-- This script implements the leaking bucket algorithm atomically in Redis.
-- Requests are queued in a bucket. The bucket "leaks" at a fixed rate.
--
-- Key difference from TokenBucket:
-- - TokenBucket: Accumulates tokens, allows bursts
-- - LeakingBucket: Fixed outflow rate, strict rate limiting
--
-- ============================================================
-- PARAMETERS
-- ============================================================
--
-- KEYS[1]: Redis key for this rate limiter entry
--   Format: rate-limiter::leaking-bucket::{ruleId}::{userId}
--
-- ARGV[1]: Current time (Unix milliseconds for precision)
--   Used to calculate leak amount
--
-- ARGV[2]: Bucket capacity (max queue size)
--   Example: 100 (max requests in queue)
--
-- ARGV[3]: Leak rate (requests per second)
--   Example: 10 (10 requests/second leak out)
--
-- ============================================================
-- RETURN VALUES
-- ============================================================
--
-- 1: Request ALLOWED (added to bucket)
-- 0: Request REJECTED (bucket full)
--
-- ============================================================

local key = KEYS[1]
local now = tonumber(ARGV[1])
local capacity = tonumber(ARGV[2])
local leak_rate = tonumber(ARGV[3]) -- requests per second

-- Get current bucket state from Redis
local bucket = redis.call('HGETALL', key)
local queue_size = 0  -- Start with empty queue
local last_leak_time = now

-- Parse bucket state if it exists
if #bucket > 0 then
    for i = 1, #bucket, 2 do
        if bucket[i] == 'queue_size' then
            queue_size = tonumber(bucket[i+1]) or 0
        elseif bucket[i] == 'last_leak_time' then
            last_leak_time = tonumber(bucket[i+1]) or now
        end
    end
end

-- Calculate leaked requests since last check (in milliseconds)
local elapsed_ms = math.max(0, now - last_leak_time)
local elapsed_sec = elapsed_ms / 1000.0
local leaked = math.floor(elapsed_sec * leak_rate)

-- Update queue size: remove leaked requests
queue_size = math.max(0, queue_size - leaked)

-- Try to add new request
if queue_size < capacity then
    -- Add request to queue
    queue_size = queue_size + 1

    -- Update Redis state atomically
    redis.call('HMSET', key, 'queue_size', tostring(queue_size), 'last_leak_time', tostring(now), 'capacity', tostring(capacity), 'leak_rate', tostring(leak_rate))
    redis.call('EXPIRE', key, 86400)  -- 24 hour TTL

    return 1  -- Request allowed
else
    -- Bucket full, reject
    -- Still update leak time for next request's calculation
    redis.call('HMSET', key, 'queue_size', tostring(queue_size), 'last_leak_time', tostring(now), 'capacity', tostring(capacity), 'leak_rate', tostring(leak_rate))
    redis.call('EXPIRE', key, 86400)

    return 0  -- Request rejected
end

-- ============================================================
-- ALGORITHM EXPLANATION
-- ============================================================
--
-- Queue Processing:
-- 1. Calculate how much time has passed since last leak check
-- 2. Calculate how many requests leaked out: elapsed_time * leak_rate
-- 3. Subtract leaked requests from queue
-- 4. Add new request if queue not at capacity
-- 5. Return 1 (allowed) or 0 (rejected)
--
-- Example Timeline:
-- T0 (t=0s):  First request: queue=[R1], leaks=0, allowed=1
-- T1 (t=0.1s): Second request: queue=[R1,R2], leaks=0, allowed=1
-- T2 (t=0.5s): Third request: queue=[R1,R2,R3], leaks=0.5*10=5→0, allowed=1
--              (5 requests should have leaked, but queue only had 3, so empty)
-- T3 (t=0.55s): Fourth request: queue=[R4], leaks=0.05*10→0, allowed=1
--
-- This ensures exactly `leak_rate` requests per second on average.
--
-- ============================================================
