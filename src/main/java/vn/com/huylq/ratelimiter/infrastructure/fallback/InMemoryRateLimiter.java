package vn.com.huylq.ratelimiter.infrastructure.fallback;

import lombok.extern.slf4j.Slf4j;
import vn.com.huylq.ratelimiter.domain.port.RateLimiter;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-Memory Rate Limiter - Fallback when Redis unavailable
 *
 * Implements the TokenBucket algorithm entirely in memory for fault tolerance.
 * When Redis is down or unreachable, this fallback ensures requests still
 * complete with rate limiting (fail-open pattern).
 *
 * Key Features:
 * - Thread-safe token bucket tracking per (ruleId, userId) pair
 * - Atomic token consumption with synchronized blocks
 * - Automatic cleanup of expired entries (every 60 seconds)
 * - Same algorithm as Redis version for consistency
 *
 * Limitations (acceptable for fallback mode):
 * - Per-instance state (not shared across multiple servers)
 * - Lost on server restart
 * - Memory-bounded by available heap
 *
 * This is acceptable because:
 * - Fallback is temporary (until Redis recovers)
 * - Alternative is to block all requests (fail-closed)
 * - Slightly loose rate limiting during failover is better than complete outage
 *
 * Note: Bean is created by RateLimiterAutoConfiguration, not auto-discovered.
 */
@Slf4j
public class InMemoryRateLimiter implements RateLimiter {

    private final Map<String, TokenBucketState> buckets = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleanupExecutor;

    // ============ CONSTANTS ============

    /** How often to cleanup expired entries (seconds) */
    private static final long CLEANUP_INTERVAL_SECONDS = 60;

    /** Entry expires after this long without access (seconds) */
    private static final long ENTRY_TTL_SECONDS = 86400; // 24 hours

    // ============ CONSTRUCTOR ============

    public InMemoryRateLimiter() {
        this.cleanupExecutor = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "RateLimiter-Cleanup");
            t.setDaemon(true);
            return t;
        });

        // Schedule cleanup task
        cleanupExecutor.scheduleAtFixedRate(
            this::cleanupExpiredEntries,
            CLEANUP_INTERVAL_SECONDS,
            CLEANUP_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );

        log.info("✓ InMemoryRateLimiter initialized (fallback mode)");
    }

    // ============ PUBLIC INTERFACE ============

    /**
     * Check if request is allowed (TokenBucket algorithm in memory)
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     * @param limit maximum requests per window
     * @param timeWindowSeconds window duration in seconds
     * @return true if allowed, false if rejected
     */
    @Override
    public boolean isAllowed(String ruleId, String userId, long limit, long timeWindowSeconds) {
        try {
            validateParameters(ruleId, userId, limit, timeWindowSeconds);

            String key = buildKey(ruleId, userId);
            long now = System.currentTimeMillis();

            // Get or create bucket state
            TokenBucketState state = buckets.computeIfAbsent(key, k -> {
                log.debug("Creating new in-memory bucket for key={}", k);
                return new TokenBucketState(limit, now);
            });

            // Synchronize on state to ensure atomic token consumption
            synchronized (state) {
                // Refill tokens based on elapsed time
                double tokensPerSecond = (double) limit / timeWindowSeconds;
                state.refill(now, limit, tokensPerSecond);

                // Check if token available
                if (state.tokens >= 1.0) {
                    state.tokens -= 1.0;
                    state.lastAccessTime = now;
                    return true;
                } else {
                    state.lastAccessTime = now;
                    return false;
                }
            }
        } catch (Exception e) {
            log.error("Error in in-memory rate limiter for rule={}, userId={}: {}",
                ruleId, userId, e.getMessage());
            // Fail open - allow request on error
            return true;
        }
    }

    /**
     * Get current token count (for monitoring)
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     * @return current tokens, -1 if not found
     */
    @Override
    public long getCurrentUsage(String ruleId, String userId) {
        try {
            String key = buildKey(ruleId, userId);
            TokenBucketState state = buckets.get(key);

            if (state == null) {
                return -1;
            }

            synchronized (state) {
                return (long) state.tokens;
            }
        } catch (Exception e) {
            log.warn("Failed to get current usage for rule={}, userId={}", ruleId, userId);
            return -1;
        }
    }

    /**
     * Reset rate limiter state (for testing)
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     */
    @Override
    public void reset(String ruleId, String userId) {
        try {
            String key = buildKey(ruleId, userId);
            buckets.remove(key);
            log.debug("Reset rate limiter for rule={}, userId={}", ruleId, userId);
        } catch (Exception e) {
            log.warn("Failed to reset rate limiter for rule={}, userId={}", ruleId, userId);
        }
    }

    // ============ PRIVATE METHODS ============

    /**
     * Build key for bucket state map
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     * @return key in format: in-memory-bucket::{ruleId}::{userId}
     */
    private String buildKey(String ruleId, String userId) {
        return "in-memory-bucket::" + ruleId + "::" + userId;
    }

    /**
     * Validate parameters
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     * @param limit max requests
     * @param timeWindowSeconds window duration
     * @throws IllegalArgumentException if invalid
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

    /**
     * Cleanup expired entries (runs periodically)
     * Removes buckets that haven't been accessed recently
     */
    private void cleanupExpiredEntries() {
        try {
            long now = System.currentTimeMillis();
            int removed = 0;

            Iterator<Map.Entry<String, TokenBucketState>> it = buckets.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, TokenBucketState> entry = it.next();
                TokenBucketState state = entry.getValue();

                // Remove if not accessed in last 24 hours
                if (now - state.lastAccessTime > ENTRY_TTL_SECONDS * 1000) {
                    it.remove();
                    removed++;
                }
            }

            if (removed > 0) {
                log.debug("Cleanup removed {} expired in-memory buckets", removed);
            }
        } catch (Exception e) {
            log.error("Error during cleanup of in-memory buckets: {}", e.getMessage());
        }
    }

    /**
     * Shutdown cleanup executor
     * Called when application shuts down
     */
    public void shutdown() {
        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ============ INNER CLASS: TokenBucketState ============

    /**
     * Internal state for a single token bucket
     */
    static class TokenBucketState {
        double tokens;
        long lastRefillTime;
        long lastAccessTime;

        TokenBucketState(long capacity, long now) {
            this.tokens = capacity;
            this.lastRefillTime = now;
            this.lastAccessTime = now;
        }

        /**
         * Refill tokens based on elapsed time
         *
         * @param now current time in milliseconds
         * @param capacity bucket capacity (max tokens)
         * @param tokensPerSecond refill rate
         */
        void refill(long now, long capacity, double tokensPerSecond) {
            long elapsedMs = now - lastRefillTime;
            double refillRate = tokensPerSecond / 1000.0; // Convert to tokens per millisecond
            double tokensToAdd = elapsedMs * refillRate;

            tokens = Math.min(capacity, tokens + tokensToAdd);
            lastRefillTime = now;
        }
    }
}
