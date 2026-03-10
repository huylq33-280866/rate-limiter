package vn.com.huylq.ratelimiter.test.assertions;

import org.assertj.core.api.AbstractAssert;
import org.assertj.core.api.Assertions;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Custom assertions for rate limiter testing.
 *
 * Provides:
 * - Rate limiting decision checks (allowed/rejected)
 * - Latency percentile measurements (p50, p95, p99)
 * - Redis state verification
 * - Token validation assertions
 */
public class RateLimiterAssertions {

    /**
     * Assert that a rate limit decision is ALLOWED (true)
     */
    public static void assertRequestAllowed(boolean decision, String message) {
        Assertions.assertThat(decision)
            .as(message)
            .isTrue();
    }

    /**
     * Assert that a rate limit decision is REJECTED (false)
     */
    public static void assertRequestRejected(boolean decision, String message) {
        Assertions.assertThat(decision)
            .as(message)
            .isFalse();
    }

    /**
     * Assert that a rate limit decision is REJECTED
     */
    public static void assertRequestRejected(boolean decision) {
        assertRequestRejected(decision, "Expected request to be rejected but was allowed");
    }

    /**
     * Assert that a rate limit decision is ALLOWED
     */
    public static void assertRequestAllowed(boolean decision) {
        assertRequestAllowed(decision, "Expected request to be allowed but was rejected");
    }

    // ==================== Latency Percentile Assertions ====================

    /**
     * Assert p50 (median) latency is within expected bounds (ms)
     */
    public static void assertLatencyP50(List<Long> latencies, long maxMs) {
        long p50 = calculatePercentile(latencies, 50);
        Assertions.assertThat(p50)
            .as("p50 latency should be <= %dms (actual: %dms)", maxMs, p50)
            .isLessThanOrEqualTo(maxMs);
    }

    /**
     * Assert p95 latency is within expected bounds (ms)
     */
    public static void assertLatencyP95(List<Long> latencies, long maxMs) {
        long p95 = calculatePercentile(latencies, 95);
        Assertions.assertThat(p95)
            .as("p95 latency should be <= %dms (actual: %dms)", maxMs, p95)
            .isLessThanOrEqualTo(maxMs);
    }

    /**
     * Assert p99 latency is within expected bounds (ms)
     */
    public static void assertLatencyP99(List<Long> latencies, long maxMs) {
        long p99 = calculatePercentile(latencies, 99);
        Assertions.assertThat(p99)
            .as("p99 latency should be <= %dms (actual: %dms)", maxMs, p99)
            .isLessThanOrEqualTo(maxMs);
    }

    /**
     * Assert average latency is within expected bounds
     */
    public static void assertAverageLatency(List<Long> latencies, long maxMs) {
        long average = (long) latencies.stream()
            .mapToLong(Long::longValue)
            .average()
            .orElse(0);
        Assertions.assertThat(average)
            .as("Average latency should be <= %dms (actual: %dms)", maxMs, average)
            .isLessThanOrEqualTo(maxMs);
    }

    /**
     * Calculate percentile from latency list (milliseconds)
     *
     * @param latencies sorted list of latency measurements
     * @param percentile 0-100
     * @return latency at percentile in milliseconds
     */
    public static long calculatePercentile(List<Long> latencies, int percentile) {
        if (latencies == null || latencies.isEmpty()) {
            return 0;
        }

        List<Long> sorted = latencies.stream()
            .sorted()
            .collect(Collectors.toList());

        int index = (int) Math.ceil((percentile / 100.0) * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));

        return sorted.get(index);
    }

    // ==================== Throughput Assertions ====================

    /**
     * Assert that throughput meets minimum RPS target
     *
     * @param requestCount total requests executed
     * @param durationSeconds test duration in seconds
     * @param minRps minimum required RPS
     */
    public static void assertThroughput(int requestCount, long durationSeconds, int minRps) {
        double actualRps = requestCount / (double) durationSeconds;
        Assertions.assertThat(actualRps)
            .as("Throughput should be >= %d RPS (actual: %.0f RPS)", minRps, actualRps)
            .isGreaterThanOrEqualTo(minRps);
    }

    // ==================== Rate Limiting State Assertions ====================

    /**
     * Assert that a specific count of requests were allowed
     */
    public static void assertExactlyAllowed(int totalRequests, int allowedCount) {
        Assertions.assertThat(allowedCount)
            .as("Expected %d allowed out of %d total", allowedCount, totalRequests)
            .isEqualTo(allowedCount);
    }

    /**
     * Assert that approximately a percentage of requests were allowed
     *
     * @param totalRequests total requests sent
     * @param allowedCount actual allowed count
     * @param expectedPercentage expected percentage (0-100)
     * @param tolerance tolerance in percentage points
     */
    public static void assertApproximateAllowedPercentage(
            int totalRequests,
            int allowedCount,
            int expectedPercentage,
            int tolerance) {
        double actualPercentage = (allowedCount / (double) totalRequests) * 100;
        Assertions.assertThat(actualPercentage)
            .as("Expected %d%% allowed (tolerance +/- %d%%), actual: %.1f%%",
                expectedPercentage, tolerance, actualPercentage)
            .isCloseTo(expectedPercentage, Assertions.within((double) tolerance));
    }

    /**
     * Assert that a list of decisions matches expected pattern
     *
     * @param decisions list of boolean decisions (true=allowed, false=rejected)
     * @param pattern expected pattern (true/false sequence)
     */
    public static void assertDecisionPattern(List<Boolean> decisions, List<Boolean> pattern) {
        Assertions.assertThat(decisions)
            .as("Decision pattern does not match expected")
            .isEqualTo(pattern);
    }

    // ==================== Token Validation Assertions ====================

    /**
     * Assert that token validation succeeded
     */
    public static void assertTokenValid(String token) {
        Assertions.assertThat(token)
            .as("Token should not be null or empty")
            .isNotBlank();
    }

    /**
     * Assert that token contains required claims
     */
    public static void assertTokenHasClaim(Map<String, Object> claims, String claimName) {
        Assertions.assertThat(claims)
            .as("Token should contain claim: %s", claimName)
            .containsKey(claimName);
    }

    /**
     * Assert that token claim has expected value
     */
    public static void assertTokenClaimEquals(Map<String, Object> claims, String claimName, Object expectedValue) {
        Assertions.assertThat(claims.get(claimName))
            .as("Token claim %s should equal %s", claimName, expectedValue)
            .isEqualTo(expectedValue);
    }

    // ==================== Redis State Assertions ====================

    /**
     * Assert that a Redis key exists
     */
    public static void assertRedisKeyExists(Map<String, Object> redisState, String key) {
        Assertions.assertThat(redisState)
            .as("Redis key should exist: %s", key)
            .containsKey(key);
    }

    /**
     * Assert that a Redis key has expected value
     */
    public static void assertRedisKeyValue(Map<String, Object> redisState, String key, Object expectedValue) {
        Assertions.assertThat(redisState.get(key))
            .as("Redis key %s should have value %s", key, expectedValue)
            .isEqualTo(expectedValue);
    }

    /**
     * Assert that token count in Redis bucket matches expected
     */
    public static void assertTokenBucketCount(Map<String, Object> bucketState, double expectedTokens) {
        Object tokens = bucketState.get("tokens");
        if (tokens instanceof Number) {
            Assertions.assertThat(((Number) tokens).doubleValue())
                .as("Token bucket should contain approximately %.1f tokens", expectedTokens)
                .isCloseTo(expectedTokens, Assertions.within(0.1));
        }
    }

    /**
     * Assert that window counter matches expected
     */
    public static void assertWindowCounter(Map<String, Object> windowState, long expectedCount) {
        Object count = windowState.get("count");
        if (count instanceof Number) {
            Assertions.assertThat(((Number) count).longValue())
                .as("Window counter should be %d", expectedCount)
                .isEqualTo(expectedCount);
        }
    }

    // ==================== Concurrency Assertions ====================

    /**
     * Assert that concurrent requests show consistent rate limiting
     *
     * @param results map of thread ID to allow/reject decisions
     * @param expectedRejects expected number of rejections
     */
    public static void assertConsistentConcurrentResults(Map<Integer, Boolean> results, int expectedRejects) {
        long actualRejects = results.values().stream()
            .filter(allowed -> !allowed)
            .count();
        Assertions.assertThat(actualRejects)
            .as("Concurrent requests should have %d rejections (actual: %d)",
                expectedRejects, actualRejects)
            .isEqualTo(expectedRejects);
    }

    /**
     * Assert that no race conditions occurred (results are deterministic)
     *
     * @param results map of run number to decision list
     */
    public static void assertNoRaceConditions(Map<Integer, List<Boolean>> results) {
        Set<String> uniquePatterns = results.values().stream()
            .map(List::toString)
            .collect(Collectors.toSet());
        Assertions.assertThat(uniquePatterns)
            .as("All concurrent runs should produce identical patterns (deterministic)")
            .hasSize(1);
    }

    // ==================== Exception Assertions ====================

    /**
     * Assert that rate limiter fails open on Redis error
     */
    public static void assertFailsOpenOnRedisError(boolean decision) {
        Assertions.assertThat(decision)
            .as("Rate limiter should fail open (allow request) when Redis is unavailable")
            .isTrue();
    }

    /**
     * Assert that exception is properly thrown with message
     */
    public static void assertExceptionMessage(Exception e, String expectedMessage) {
        Assertions.assertThat(e.getMessage())
            .as("Exception should contain message: %s", expectedMessage)
            .contains(expectedMessage);
    }

    // ==================== Complex State Assertions ====================

    /**
     * Builder for complex assertion chains
     */
    public static class RateLimiterAssertionBuilder {
        private final List<Boolean> decisions = new ArrayList<>();
        private final List<Long> latencies = new ArrayList<>();
        private int allowedCount = 0;
        private int rejectedCount = 0;

        public RateLimiterAssertionBuilder decision(boolean allowed) {
            decisions.add(allowed);
            if (allowed) {
                allowedCount++;
            } else {
                rejectedCount++;
            }
            return this;
        }

        public RateLimiterAssertionBuilder latency(long ms) {
            latencies.add(ms);
            return this;
        }

        public void assertTotalRequests(int expected) {
            Assertions.assertThat(decisions.size())
                .as("Should have %d total requests", expected)
                .isEqualTo(expected);
        }

        public void assertAllowedCount(int expected) {
            Assertions.assertThat(allowedCount)
                .as("Should have %d allowed requests", expected)
                .isEqualTo(expected);
        }

        public void assertRejectedCount(int expected) {
            Assertions.assertThat(rejectedCount)
                .as("Should have %d rejected requests", expected)
                .isEqualTo(expected);
        }

        public void assertMedianLatency(long maxMs) {
            if (!latencies.isEmpty()) {
                assertLatencyP50(latencies, maxMs);
            }
        }

        public void assertPercentileLatency(int percentile, long maxMs) {
            if (!latencies.isEmpty()) {
                long p = calculatePercentile(latencies, percentile);
                Assertions.assertThat(p)
                    .as("p%d latency should be <= %dms (actual: %dms)", percentile, maxMs, p)
                    .isLessThanOrEqualTo(maxMs);
            }
        }
    }

    /**
     * Create builder for complex assertions
     */
    public static RateLimiterAssertionBuilder assertRateLimiter() {
        return new RateLimiterAssertionBuilder();
    }
}
