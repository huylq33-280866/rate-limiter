-- ============================================================
-- FixedWindowCounter Rate Limiting Algorithm - Lua Script
-- ============================================================
--
-- Simple counter-based rate limiting using fixed time windows.
-- Every N seconds, the counter resets.
--
-- ============================================================
-- PARAMETERS
-- ============================================================
--
-- KEYS[1]: Redis key for counter
--
-- ARGV[1]: Current time (Unix seconds)
--
-- ARGV[2]: Limit (requests per window)
--
-- ARGV[3]: Window size (seconds)
--
-- ============================================================

local key = KEYS[1]
local now = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])
local window_seconds = tonumber(ARGV[3])

-- Get current counter data
local bucket = redis.call('HGETALL', key)
local count = 0
local window_start = now

-- Parse stored state
if #bucket > 0 then
    for i = 1, #bucket, 2 do
        if bucket[i] == 'count' then
            count = tonumber(bucket[i+1]) or 0
        elseif bucket[i] == 'window_start' then
            window_start = tonumber(bucket[i+1]) or now
        end
    end
end

-- Check if window has expired
local window_elapsed = now - window_start
if window_elapsed >= window_seconds then
    -- Window expired, reset counter
    count = 0
    window_start = now
end

-- Try to increment counter
if count < limit then
    count = count + 1
    -- Atomic HMSET for consistency
    redis.call('HMSET', key, 'count', tostring(count), 'window_start', tostring(window_start), 'limit', tostring(limit), 'window_seconds', tostring(window_seconds))
    redis.call('EXPIRE', key, window_seconds + 10)  -- TTL slightly longer than window
    return 1  -- Allowed
else
    -- Limit exceeded
    return 0  -- Rejected
end
