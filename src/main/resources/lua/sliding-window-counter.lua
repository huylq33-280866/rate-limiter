-- ============================================================
-- SlidingWindowCounter Rate Limiting Algorithm - Lua Script
-- ============================================================
--
-- Uses multiple counters for sub-windows within the larger window.
-- Better accuracy than FixedWindow, lower memory than SlidingWindowLog.
--
-- Example: 100 requests per minute (60 seconds)
-- Use 60 1-second sub-windows
-- Each request updates 2 adjacent sub-window counters
-- Sum last N sub-windows to get current count
--
-- ============================================================

local key = KEYS[1]
local now = tonumber(ARGV[1])  -- Current time in seconds
local limit = tonumber(ARGV[2])
local window_seconds = tonumber(ARGV[3])

-- Use 10 sub-windows for accuracy
-- Smaller sub-windows = better accuracy but more memory
local sub_window_count = 10
local sub_window_size = math.ceil(window_seconds / sub_window_count)

-- Current sub-window index
local current_window = math.floor(now / sub_window_size)
local prev_window = current_window - 1

-- Get counters for current and previous windows
local current_count = tonumber(redis.call('HGET', key, 'w' .. current_window) or '0')
local prev_count = tonumber(redis.call('HGET', key, 'w' .. prev_window) or '0')

-- Calculate weight: how much of the previous window overlaps current request time
-- If now is at 25% into current window, previous window contributes 75%
local current_window_start = current_window * sub_window_size
local weight = (sub_window_size - (now - current_window_start)) / sub_window_size

-- Calculate total count with weighted average
-- Interpolate: count = current_window_count + (previous_window_count * weight)
local estimated_count = current_count + (prev_count * weight)

-- Check if can add new request
if estimated_count < limit then
    -- Increment current window counter
    redis.call('HINCRBY', key, 'w' .. current_window, 1)

    -- Clean up: delete the window that's definitely expired (current - 2)
    local old_window = current_window - 2
    redis.call('HDEL', key, 'w' .. old_window)

    -- Set expiration
    redis.call('EXPIRE', key, window_seconds + 10)

    return 1  -- Request allowed
else
    -- Limit exceeded
    return 0  -- Request rejected
end
