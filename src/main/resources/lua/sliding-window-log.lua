-- ============================================================
-- SlidingWindowLog Rate Limiting Algorithm - Lua Script
-- ============================================================
--
-- Maintains exact request timestamps in a sliding window.
-- When new request arrives, removes all timestamps outside window.
-- If count < limit, adds new timestamp and allows.
--
-- Data Structure: Redis Sorted Set (timestamp -> score)
-- - Score = timestamp (unix milliseconds)
-- - Member = request sequence number
--
-- Pros:
-- - Precise, no boundary issues
-- - Exact enforcement of rate limit
--
-- Cons:
-- - Memory overhead (stores every request timestamp)
-- - Slower at high traffic (removes old entries each request)
--
-- ============================================================

local key = KEYS[1]
local now = tonumber(ARGV[1])  -- Current time in milliseconds
local limit = tonumber(ARGV[2])
local window_ms = tonumber(ARGV[3])  -- Window size in milliseconds

-- Remove all entries outside the sliding window
-- Keep only entries from (now - window_ms) to now
local window_start = now - window_ms
redis.call('ZREMRANGEBYSCORE', key, 0, window_start)

-- Count current entries in window
local count = redis.call('ZCARD', key)

-- Try to add new request
if count < limit then
    -- Add timestamp as score and sequence as member
    -- Use incrementing counter for unique members
    local sequence = redis.call('INCR', key .. ':seq')
    redis.call('ZADD', key, now, tostring(sequence))

    -- Set expiration: window size + 1 second buffer
    redis.call('EXPIRE', key, math.ceil(window_ms / 1000) + 1)
    redis.call('EXPIRE', key .. ':seq', math.ceil(window_ms / 1000) + 1)

    return 1  -- Request allowed
else
    -- Limit exceeded
    return 0  -- Request rejected
end
