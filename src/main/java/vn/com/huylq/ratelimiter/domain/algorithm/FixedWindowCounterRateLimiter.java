package vn.com.huylq.ratelimiter.domain.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import vn.com.huylq.ratelimiter.domain.port.RateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.fallback.InMemoryRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.lua.LuaScriptExecutor;
import java.util.Arrays;

/**
 * FixedWindowCounter Rate Limiting Algorithm
 *
 * Algorithm:
 * - Divide time into fixed windows (e.g., per second, per minute)
 * - Count requests in current window
 * - When window ends, counter resets
 * - If count < limit, allow; else reject
 *
 * Use Case: Simple rate limiting, easy to understand
 *
 * Pros:
 * - Very simple to implement
 * - Predictable behavior
 * - Works well for coarse-grained limits (per hour, per day)
 *
 * Cons:
 * - Boundary condition: requests can spike at window edges
 * - At t=59s, 100 requests allowed. At t=60s, 100 more allowed.
 *   So 200 requests in 2 seconds (burst at boundary)
 *
 * Redis Data Structure:
 * Hash with fields:
 *   - count: Requests in current window
 *   - window_start: Timestamp when current window started
 */
@Slf4j
public class FixedWindowCounterRateLimiter implements RateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final LuaScriptExecutor luaScriptExecutor;
    private final InMemoryRateLimiter inMemoryFallback;

    private static final String REDIS_KEY_PREFIX = "rate-limiter::fixed-window";

    public FixedWindowCounterRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptExecutor luaScriptExecutor,
            InMemoryRateLimiter inMemoryFallback) {
        this.redisTemplate = redisTemplate;
        this.luaScriptExecutor = luaScriptExecutor;
        this.inMemoryFallback = inMemoryFallback;
        log.info("✓ FixedWindowCounterRateLimiter initialized");
    }

    @Override
    public boolean isAllowed(String ruleId, String userId, long limit, long timeWindowSeconds) {
        try {
            validateParameters(ruleId, userId, limit, timeWindowSeconds);

            String key = buildKey(ruleId, userId);
            long now = System.currentTimeMillis() / 1000;

            Long result = luaScriptExecutor.executeLuaScript(
                    "fixed-window-counter",
                    Arrays.asList(key),
                    Arrays.asList(
                            String.valueOf(now),
                            String.valueOf(limit),
                            String.valueOf(timeWindowSeconds)
                    )
            );

            return result != null && result == 1L;
        } catch (IllegalArgumentException e) {
            // Validation error - reject request
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
            java.util.Map<Object, Object> bucket = redisTemplate.opsForHash().entries(key);

            if (bucket == null || bucket.isEmpty()) {
                return 0;
            }

            long count = Long.parseLong(bucket.getOrDefault("count", "0").toString());
            return count;

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
