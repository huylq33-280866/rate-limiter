package vn.com.huylq.ratelimiter.domain.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import vn.com.huylq.ratelimiter.domain.port.RateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.fallback.InMemoryRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.lua.LuaScriptExecutor;
import java.util.Arrays;

/**
 * SlidingWindowLog Rate Limiting Algorithm
 *
 * Algorithm:
 * - Maintains precise log of request timestamps
 * - For each request: remove timestamps older than window, check count
 * - Uses Redis Sorted Set (score = timestamp)
 *
 * Perfect accuracy: No boundary effects at window edges
 * vs FixedWindow: Fixed window has boundary spikes (100 at t=59s, 100 at t=60s)
 * SlidingWindowLog: Max 100 in any 60-second window
 *
 * Trade-off: More memory, slower (removes old entries each request)
 */
@Slf4j
public class SlidingWindowLogRateLimiter implements RateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final LuaScriptExecutor luaScriptExecutor;
    private final InMemoryRateLimiter inMemoryFallback;

    private static final String REDIS_KEY_PREFIX = "rate-limiter::sliding-window-log";

    public SlidingWindowLogRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptExecutor luaScriptExecutor,
            InMemoryRateLimiter inMemoryFallback) {
        this.redisTemplate = redisTemplate;
        this.luaScriptExecutor = luaScriptExecutor;
        this.inMemoryFallback = inMemoryFallback;
        log.info("✓ SlidingWindowLogRateLimiter initialized");
    }

    @Override
    public boolean isAllowed(String ruleId, String userId, long limit, long timeWindowSeconds) {
        try {
            validateParameters(ruleId, userId, limit, timeWindowSeconds);

            String key = buildKey(ruleId, userId);
            long now = System.currentTimeMillis();
            long windowMs = timeWindowSeconds * 1000;

            Long result = luaScriptExecutor.executeLuaScript(
                    "sliding-window-log",
                    Arrays.asList(key),
                    Arrays.asList(
                            String.valueOf(now),
                            String.valueOf(limit),
                            String.valueOf(windowMs)
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
            redisTemplate.delete(key + ":seq");
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
            Long count = redisTemplate.opsForZSet().size(key);
            return count != null ? count : 0;
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
