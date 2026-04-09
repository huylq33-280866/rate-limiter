package vn.com.huylq.ratelimiter.domain.algorithm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import vn.com.huylq.ratelimiter.domain.port.RateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.fallback.InMemoryRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.lua.LuaScriptExecutor;
import java.util.Map;

/**
 * TokenBucket Rate Limiting Algorithm
 *
 * Algorithm:
 * - Bucket starts with N tokens (capacity)
 * - Tokens refill at rate R tokens/second
 * - Request consumes 1 token
 * - If bucket empty: Request rejected
 *
 * Use Case: Burstiness allowed (initial capacity absorbs spikes)
 *
 * Redis Data Structure:
 * Hash with fields:
 *   - tokens: Current token count (float with decimals for fractional tokens)
 *   - last_refill: Unix timestamp of last refill
 *
 * Example Redis State:
 *   Key: rate-limiter::token-bucket::premium-api::user-123
 *   {
 *     "tokens": 47.5,
 *     "last_refill": 1709971200
 *   }
 *
 * Note: Bean is created by RateLimiterAutoConfiguration, not auto-discovered.
 */
@Slf4j
public class TokenBucketRateLimiter implements RateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;
    private final LuaScriptExecutor luaScriptExecutor;
    private final InMemoryRateLimiter inMemoryFallback;

    // ============ CONSTANTS ============

    /** Redis key prefix for token bucket state */
    private static final String REDIS_KEY_PREFIX = "rate-limiter::token-bucket";

    // ============ CONSTRUCTOR ============

    public TokenBucketRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptExecutor luaScriptExecutor,
            InMemoryRateLimiter inMemoryFallback) {

        this.redisTemplate = redisTemplate;
        this.luaScriptExecutor = luaScriptExecutor;
        this.inMemoryFallback = inMemoryFallback;

        log.info("✓ TokenBucketRateLimiter initialized");
    }

    // ============ PUBLIC INTERFACE ============

    /**
     * Check if request is allowed under rate limit (TokenBucket algorithm)
     *
     * @param ruleId unique rule identifier
     * @param userId user/entity identifier
     * @param limit maximum requests allowed in window
     * @param timeWindowSeconds duration of the rate limit window
     * @return true if request is allowed, false if rate limited
     */
    @Override
    public boolean isAllowed(String ruleId, String userId, long limit, long timeWindowSeconds) {
        try {
            // Validate parameters before processing
            validateParameters(ruleId, userId, limit, timeWindowSeconds);
            return checkRedisTokenBucket(ruleId, userId, limit, timeWindowSeconds);
        } catch (IllegalArgumentException e) {
            // Validation error - reject request (don't fail open for validation failures)
            log.warn("Invalid parameters for rule={}, userId={}: {}", ruleId, userId, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Redis check failed for rule={}, userId={}: {}. Falling back to in-memory.",
                    ruleId, userId, e.getMessage());

            // Fall back to in-memory (fail-open for infrastructure failures)
            return inMemoryFallback.isAllowed(ruleId, userId, limit, timeWindowSeconds);
        }
    }

    /**
     * Get current token count for a key (for monitoring/debugging)
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     * @return current token count, or -1 if key doesn't exist
     */
    @Override
    public long getCurrentUsage(String ruleId, String userId) {
        try {
            String key = buildRedisKey(ruleId, userId);
            Map<Object, Object> bucket = redisTemplate.opsForHash().entries(key);

            if (bucket == null || bucket.isEmpty()) {
                return -1;
            }

            // Extract current tokens and last_refill time
            double tokens = Double.parseDouble(bucket.getOrDefault("tokens", "0").toString());
            long lastRefill = Long.parseLong(bucket.getOrDefault("last_refill", "0").toString());
            long now = System.currentTimeMillis() / 1000;

            // Recalculate tokens with refill
            long limit = (long) Double.parseDouble(bucket.getOrDefault("limit", "0").toString());
            long timeWindowSeconds = (long) Double.parseDouble(bucket.getOrDefault("window", "3600").toString());
            double tokensPerSecond = (double) limit / timeWindowSeconds;

            long elapsed = Math.max(0, now - lastRefill);
            long refilled = (long) Math.min(limit, tokens + (elapsed * tokensPerSecond));

            return refilled;

        } catch (Exception e) {
            log.warn("Failed to get current usage for rule={}, userId={}", ruleId, userId, e);
            return -1;
        }
    }

    /**
     * Reset rate limiter state for testing
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     */
    @Override
    public void reset(String ruleId, String userId) {
        try {
            String key = buildRedisKey(ruleId, userId);
            redisTemplate.delete(key);
            inMemoryFallback.reset(ruleId, userId);
            log.debug("Reset rate limiter for rule={}, userId={}", ruleId, userId);
        } catch (Exception e) {
            log.warn("Failed to reset rate limiter for rule={}, userId={}", ruleId, userId, e);
        }
    }

    // ============ PRIVATE METHODS ============

    /**
     * Check token bucket via Redis using Lua script (atomic operation)
     *
     * Algorithm Steps:
     * 1. Get current bucket state (tokens, last_refill)
     * 2. Calculate time since last refill
     * 3. Refill tokens: tokens = min(capacity, tokens + elapsed * rate)
     * 4. Check if enough tokens: if tokens >= 1, consume 1 and return true
     * 5. Update bucket state with EXPIRE TTL
     * 6. Return result
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     * @param limit max requests per window
     * @param timeWindowSeconds window duration in seconds
     * @return true if allowed, false if rejected
     */
    private boolean checkRedisTokenBucket(
            String ruleId,
            String userId,
            long limit,
            long timeWindowSeconds) {

        String key = buildRedisKey(ruleId, userId);
        long now = System.currentTimeMillis() / 1000; // Convert to seconds

        // Calculate tokens per second (refill rate)
        // Example: 100 requests per 3600 seconds = 0.0278 tokens/sec
        double tokensPerSecond = (double) limit / timeWindowSeconds;

        // Execute Lua script for atomic token bucket operation
        Long result = luaScriptExecutor.executeLuaScript(
            "token-bucket",
            java.util.List.of(key),
            java.util.List.of(
                String.valueOf(now),
                String.valueOf(limit),
                String.valueOf(tokensPerSecond),
                String.valueOf(limit)
            )
        );

        return result != null && result == 1L;
    }

    /**
     * Build Redis key for this rate limiter entry
     *
     * Format: rate-limiter::token-bucket::{ruleId}::{userId}
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     * @return Redis key
     */
    private String buildRedisKey(String ruleId, String userId) {
        return String.format("%s::%s::%s", REDIS_KEY_PREFIX, ruleId, userId);
    }

    /**
     * Validate parameters before execution
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     * @param limit max requests
     * @param timeWindowSeconds window duration
     * @throws IllegalArgumentException if parameters invalid
     */
    private void validateParameters(String ruleId, String userId, long limit, long timeWindowSeconds) {
        if (ruleId == null || ruleId.isEmpty()) {
            throw new IllegalArgumentException("ruleId cannot be null or empty");
        }
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null or empty");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (timeWindowSeconds <= 0) {
            throw new IllegalArgumentException("timeWindowSeconds must be > 0");
        }
    }

}
