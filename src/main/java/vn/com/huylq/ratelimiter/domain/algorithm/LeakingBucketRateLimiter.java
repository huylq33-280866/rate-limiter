package vn.com.huylq.ratelimiter.domain.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import vn.com.huylq.ratelimiter.domain.port.RateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.fallback.InMemoryRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.lua.LuaScriptExecutor;
import java.util.Arrays;

/**
 * LeakingBucket Rate Limiting Algorithm
 *
 * Algorithm:
 * - Requests queue in a bucket up to capacity
 * - Bucket "leaks" requests at a fixed rate (e.g., 10/sec)
 * - New request: allowed if queue not full
 * - No bursts allowed (fixed outflow rate)
 *
 * Use Case: Strict rate limiting where consistent, predictable throughput is required
 *
 * vs TokenBucket:
 * - TokenBucket: Allows bursts (accumulated tokens), variable rate
 * - LeakingBucket: Fixed rate, no bursts, strict queue discipline
 *
 * Redis Data Structure:
 * Hash with fields:
 *   - queue_size: Number of requests in queue (int)
 *   - last_leak_time: Timestamp of last leak check (unix milliseconds)
 */
@Slf4j
public class LeakingBucketRateLimiter implements RateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final LuaScriptExecutor luaScriptExecutor;
    private final InMemoryRateLimiter inMemoryFallback;

    /** Redis key prefix for leaking bucket state */
    private static final String REDIS_KEY_PREFIX = "rate-limiter::leaking-bucket";

    /** Default bucket capacity */
    private static final long DEFAULT_CAPACITY = 100L;

    /** TTL for rate limiter keys (24 hours) */
    private static final long REDIS_KEY_TTL_SECONDS = 86400L;

    // ============ CONSTRUCTOR ============

    public LeakingBucketRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptExecutor luaScriptExecutor,
            InMemoryRateLimiter inMemoryFallback) {
        this.redisTemplate = redisTemplate;
        this.luaScriptExecutor = luaScriptExecutor;
        this.inMemoryFallback = inMemoryFallback;
        log.info("✓ LeakingBucketRateLimiter initialized");
    }

    // ============ PUBLIC INTERFACE ============

    @Override
    public boolean isAllowed(String ruleId, String userId, long limit, long timeWindowSeconds) {
        try {
            validateParameters(ruleId, userId, limit, timeWindowSeconds);
            return checkRedisLeakingBucket(ruleId, userId, limit, timeWindowSeconds);
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
                return 0;  // Queue is empty
            }

            // Extract current queue size
            long queueSize = Long.parseLong(bucket.getOrDefault("queue_size", "0").toString());
            return queueSize;

        } catch (Exception e) {
            log.warn("Failed to get current usage for rule={}, userId={}", ruleId, userId, e);
            return -1;
        }
    }

    // ============ PRIVATE METHODS ============

    /**
     * Check rate limit using Redis Lua script for LeakingBucket
     *
     * @param ruleId Rate limit rule ID
     * @param userId User identifier
     * @param capacity Bucket capacity (max queue size)
     * @param leakRatePerSecond Leak rate (requests/second)
     * @return true if request allowed, false if rejected
     */
    private boolean checkRedisLeakingBucket(String ruleId, String userId, long capacity, long leakRatePerSecond) {
        String key = buildKey(ruleId, userId);
        long now = System.currentTimeMillis();

        // Leak rate: requests per second
        double leakRate = (double) capacity / leakRatePerSecond;

        Long result = luaScriptExecutor.executeLuaScript(
                "leaking-bucket",
                Arrays.asList(key),
                Arrays.asList(
                        String.valueOf(now),
                        String.valueOf(capacity),
                        String.valueOf(leakRate)
                )
        );

        return result != null && result == 1L;
    }

    /**
     * Build Redis key for this bucket
     *
     * @param ruleId Rate limit rule ID
     * @param userId User identifier
     * @return Redis key string
     */
    private String buildKey(String ruleId, String userId) {
        return REDIS_KEY_PREFIX + "::" + ruleId + "::" + userId;
    }

    /**
     * Validate input parameters
     *
     * @param ruleId Rate limit rule ID
     * @param userId User identifier
     * @param limit Bucket capacity
     * @param timeWindowSeconds Time window in seconds (used for leak rate)
     * @throws IllegalArgumentException if any parameter is invalid
     */
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
