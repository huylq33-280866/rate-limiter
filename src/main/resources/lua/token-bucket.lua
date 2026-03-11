-- ============================================================
-- TokenBucket Rate Limiting Algorithm - Lua Script
-- ============================================================
--
-- This script is executed atomically in Redis to implement
-- the token bucket algorithm. All operations are atomic -
-- no race conditions possible even with concurrent requests.
--
-- ============================================================
-- PARAMETERS
-- ============================================================
--
-- KEYS[1]: Redis key for this rate limiter entry
--   Format: rate-limiter::token-bucket::{ruleId}::{userId}
--
-- ARGV[1]: Current time (Unix seconds)
--   Used to calculate how long since last refill
--
-- ARGV[2]: Bucket capacity (max tokens)
--   Example: 100 (requests per window)
--
-- ARGV[3]: Tokens per second (refill rate)
--   Example: 0.0278 (100 requests / 3600 seconds)
--
-- ARGV[4]: Bucket capacity again (for refill max)
--   Same as ARGV[2], passed for consistency
--
-- ============================================================
-- RETURN VALUES
-- ============================================================
--
-- 1: Request ALLOWED (tokens available, consumed)
-- 0: Request REJECTED (bucket empty)
--
-- ============================================================
-- ALGORITHM LOGIC
-- ============================================================

local key = KEYS[1]
local now = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local tokens_per_second = tonumber(ARGV[3])
local bucket_capacity = tonumber(ARGV[4])

-- TODO: Implement TokenBucket algorithm
--
-- Steps:
-- 1. GET current bucket state from Redis hash:
--    - tokens: Current token count (float)
--    - last_refill: Timestamp of last refill
--
-- 2. If key doesn't exist:
--    - tokens = bucket_capacity (start full)
--    - last_refill = now
--
-- 3. Calculate time since last refill:
--    - elapsed = max(0, now - last_refill)
--
-- 4. Refill tokens based on elapsed time:
--    - refilled = tokens + (elapsed * tokens_per_second)
--    - refilled = min(bucket_capacity, refilled)
--
-- 5. Check if request can be allowed:
--    - if refilled >= 1:
--        - Consume 1 token
--        - Update Redis state: tokens = refilled - 1
--        - Update Redis state: last_refill = now
--        - Set TTL: EXPIRE key 86400 (24 hours)
--        - RETURN 1 (allowed)
--    - else:
--        - RETURN 0 (rejected)
--
-- ============================================================
-- IMPLEMENTATION OUTLINE
-- ============================================================

-- Step 1: Get current bucket state
local bucket = redis.call('HGETALL', key)
local tokens = bucket_capacity
local last_refill = now

if #bucket > 0 then
    -- Key exists, extract fields
    -- HGETALL returns: {field1, value1, field2, value2, ...}
    for i = 1, #bucket, 2 do
        if bucket[i] == 'tokens' then
            tokens = tonumber(bucket[i+1]) or bucket_capacity
        elseif bucket[i] == 'last_refill' then
            last_refill = tonumber(bucket[i+1]) or now
        end
    end
end

-- Step 2: Calculate refill
-- Calculate elapsed time since last refill (in seconds)
-- Use math.max(0, ...) to handle clock skew (time going backward)
local elapsed = math.max(0, now - last_refill)

-- Refill tokens: add tokens based on elapsed time
-- Cap at bucket capacity (can't exceed max)
local refilled = math.min(bucket_capacity, tokens + (elapsed * tokens_per_second))

-- Step 3: Check and consume token
if refilled >= 1 then
    -- Token available, consume it
    -- Update Redis state with new token count and refill time
    local new_tokens = refilled - 1

    -- Atomic HMSET for both tokens and last_refill in one operation
    redis.call('HMSET', key, 'tokens', tostring(new_tokens), 'last_refill', tostring(now), 'limit', tostring(limit), 'window', tostring(bucket_capacity))
    redis.call('EXPIRE', key, 86400)
    return 1
else
    -- No tokens available, reject
    -- Still update last_refill for accurate refill calculation next time
    -- Atomic HMSET to maintain consistency
    redis.call('HMSET', key, 'tokens', tostring(refilled), 'last_refill', tostring(now), 'limit', tostring(limit), 'window', tostring(bucket_capacity))
    redis.call('EXPIRE', key, 86400)
    return 0
end

-- ============================================================
-- TESTING/DEBUGGING NOTES
-- ============================================================
--
-- Test Case 1: First request (empty bucket)
--   Expected: Initialize bucket full, consume 1, return 1
--
-- Test Case 2: Rapid requests within capacity
--   Expected: Consume tokens sequentially, return 1 until limit hit
--
-- Test Case 3: Rapid requests exceeding capacity
--   Expected: Allow burst up to capacity, then start rejecting
--
-- Test Case 4: Refill after time passes
--   Expected: Tokens refill based on elapsed time and rate
--
-- Test Case 5: Concurrent requests (race condition test)
--   Expected: Redis atomicity ensures no race conditions
--
-- ============================================================
-- EDGE CASES
-- ============================================================
--
-- Edge Case 1: Clock skew (backward time jump)
--   - elapsed becomes negative
--   - Use math.max(0, elapsed) to handle
--
-- Edge Case 2: Very large time gaps
--   - Refilled tokens exceed capacity
--   - Use min(capacity, refilled) to cap
--
-- Edge Case 3: Fractional tokens
--   - 100 requests/hour = 0.0278 tokens/sec
--   - Need float precision, not int
--   - Redis stores as string, use tonumber()
--
-- ============================================================
