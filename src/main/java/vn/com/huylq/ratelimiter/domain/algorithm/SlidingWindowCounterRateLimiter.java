package vn.com.huylq.ratelimiter.domain.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import vn.com.huylq.ratelimiter.domain.port.RateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.fallback.InMemoryRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.lua.LuaScriptExecutor;
import java.util.Arrays;

/**
 * SlidingWindowCounter Rate Limiting Algorithm
 *
 * Algorithm:
 * - Divides window into sub-windows (e.g., 10 sub-windows per main window)
 * - Tracks counter for each sub-window
 * - When request arrives: interpolate count from current + previous windows
 * - More accurate than FixedWindow, less memory than SlidingWindowLog
 *
 * Balance: Good accuracy + reasonable memory usage
 *
 * Example: 100 requests per 60 seconds
 * Uses 10 x 6-second sub-windows
 * At window boundary: interpolates between adjacent counters
 */
@Slf4j
public class SlidingWindowCounterRateLimiter implements RateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final LuaScriptExecutor luaScriptExecutor;
    private final InMemoryRateLimiter inMemoryFallback;

    private static final String REDIS_KEY_PREFIX = "rate-limiter::sliding-window-counter";

    public SlidingWindowCounterRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptExecutor luaScriptExecutor,
            InMemoryRateLimiter inMemoryFallback) {
        this.redisTemplate = redisTemplate;
        this.luaScriptExecutor = luaScriptExecutor;
        this.inMemoryFallback = inMemoryFallback;
        log.info("✓ SlidingWindowCounterRateLimiter initialized");
    }

    @Override
    public boolean isAllowed(String ruleId, String userId, long limit, long timeWindowSeconds) {
        try {
            validateParameters(ruleId, userId, limit, timeWindowSeconds);

            String key = buildKey(ruleId, userId);
            long now = System.currentTimeMillis() / 1000;  // Convert to seconds

            Long result = luaScriptExecutor.executeLuaScript(
                    "sliding-window-counter",
                    Arrays.asList(key),
                    Arrays.asList(
                            String.valueOf(now),
                            String.valueOf(limit),
                            String.valueOf(timeWindowSeconds)
                    )
            );

            return result != null && result == 1L;
        } catch (IllegalArgumentException e) {
            log.warn("Invalid parameters for rule={}, userId={}: {}", ruleId, userId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Redis check failed for rule={}, userId={}: {}. Falling back to in-memory.",
                    ruleId, userId, e.getMessage());
            return inMemoryFallback.isAllowed(ruleId, userId, limit, timeWindowSeconds);
        }
    }

    @Override
    public void reset(String ruleId, String userId) {
        try {
            String key = buildKey(ruleId, userId);
            redisTemplate.delete(key);
            inMemoryFallback.reset(ruleId, userId);
            log.debug("Reset rate limiter for rule={}, userId={}", ruleId, userId);
        } catch (Exception e) {
            log.warn("Failed to reset rate limiter for rule={}, userId={}", ruleId, userId, e);
        }
    }

    @Override
    public long getCurrentUsage(String ruleId, String userId) {
        try {
            String key = buildKey(ruleId, userId);
            java.util.Map<Object, Object> windows = redisTemplate.opsForHash().entries(key);

            if (windows == null || windows.isEmpty()) {
                return 0;
            }

            // Sum all sub-window counters for total count
            long total = 0;
            for (Object value : windows.values()) {
                total += Long.parseLong(value.toString());
            }
            return total;
        } catch (Exception e) {
            log.warn("Failed to get current usage for rule={}, userId={}", ruleId, userId, e);
            return -1;
        }
    }

    private String buildKey(String ruleId, String userId) {
        return REDIS_KEY_PREFIX + "::" + ruleId + "::" + userId;
    }

    private void validateParameters(String ruleId, String userId, long limit, long timeWindowSeconds) {
        if (ruleId == null || ruleId.trim().isEmpty()) {
            throw new IllegalArgumentException("ruleId cannot be null or empty");
        }
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null or empty");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (timeWindowSeconds <= 0) {
            throw new IllegalArgumentException("timeWindowSeconds must be positive");
        }
    }
}
