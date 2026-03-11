package vn.com.huylq.ratelimiter.domain.port;

/**
 * Rate Limiter Domain Port Interface
 *
 * This is a domain port (hexagonal architecture) defining the interface
 * that rate limiting algorithms must implement. Different implementations
 * (TokenBucket, LeakingBucket, SlidingWindow, etc.) will implement this.
 *
 * Key Responsibilities:
 * - Determine if a request should be allowed or rejected
 * - Track usage across time windows
 * - Support fail-open pattern (always allow on infrastructure failure)
 */
public interface RateLimiter {

    /**
     * Check if request is allowed under rate limit
     *
     * @param ruleId unique rule identifier
     * @param userId user/entity identifier
     * @param limit maximum requests allowed in window
     * @param timeWindowSeconds duration of the rate limit window
     * @return true if request is allowed, false if rate limited
     */
    boolean isAllowed(String ruleId, String userId, long limit, long timeWindowSeconds);

    /**
     * Get current usage/token count for a key (for monitoring/debugging)
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     * @return current token count or usage, -1 if key doesn't exist
     */
    long getCurrentUsage(String ruleId, String userId);

    /**
     * Reset rate limiter state for testing/maintenance
     *
     * @param ruleId rule identifier
     * @param userId user identifier
     */
    void reset(String ruleId, String userId);
}
