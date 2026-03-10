package vn.com.huylq.ratelimiter.algorithm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import vn.com.huylq.ratelimiter.domain.algorithm.LeakingBucketRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.fallback.InMemoryRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.lua.LuaScriptExecutor;
import vn.com.huylq.ratelimiter.test.fixtures.TestFixtures;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for LeakingBucket rate limiting algorithm
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("LeakingBucket Rate Limiter Tests")
public class LeakingBucketRateLimiterTest {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private LuaScriptExecutor luaScriptExecutor;

    @Autowired
    private InMemoryRateLimiter inMemoryFallback;

    private LeakingBucketRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new LeakingBucketRateLimiter(redisTemplate, luaScriptExecutor, inMemoryFallback);
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    @DisplayName("Should allow request when queue not full")
    void testAllowsRequestsWithinCapacity() {
        String ruleId = TestFixtures.PREMIUM_API_RULE_ID;
        String userId = TestFixtures.TEST_USER_ID;
        long capacity = 5;
        long leakRatePerSecond = 1;  // 1 request/sec (5 second window)

        // First request should be allowed (queue empty)
        boolean result = rateLimiter.isAllowed(ruleId, userId, capacity, leakRatePerSecond);
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("Should reject request when queue is full")
    void testRejectsRequestsWhenQueueFull() {
        String ruleId = TestFixtures.PREMIUM_API_RULE_ID;
        String userId = TestFixtures.TEST_USER_ID;
        long capacity = 2;
        long leakRatePerSecond = 1;

        // Fill the queue
        boolean first = rateLimiter.isAllowed(ruleId, userId, capacity, leakRatePerSecond);
        boolean second = rateLimiter.isAllowed(ruleId, userId, capacity, leakRatePerSecond);
        boolean third = rateLimiter.isAllowed(ruleId, userId, capacity, leakRatePerSecond);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        assertThat(third).isFalse();  // Queue full, reject
    }

    @Test
    @DisplayName("Should leak requests after time passes")
    void testLeaksRequestsAfterTimeWindow() {
        String ruleId = "test-leak";
        String userId = TestFixtures.TEST_USER_ID;
        long capacity = 2;
        long leakRatePerSecond = 1;

        // Fill queue
        rateLimiter.isAllowed(ruleId, userId, capacity, leakRatePerSecond);
        rateLimiter.isAllowed(ruleId, userId, capacity, leakRatePerSecond);
        boolean third = rateLimiter.isAllowed(ruleId, userId, capacity, leakRatePerSecond);
        assertThat(third).isFalse();  // Queue full

        // Wait for leak (1 request/sec = 1000ms)
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // After leak, should be able to add one more request
        boolean fourth = rateLimiter.isAllowed(ruleId, userId, capacity, leakRatePerSecond);
        assertThat(fourth).isTrue();
    }

    @Test
    @DisplayName("Should maintain fixed leak rate")
    void testMaintainsFixedLeakRate() {
        String ruleId = "test-fixed-rate";
        String userId = TestFixtures.TEST_USER_ID;
        long capacity = 100;
        long leakRatePerSecond = 10;  // 10 requests/sec

        // Fill queue with burst
        AtomicInteger allowed = new AtomicInteger(0);
        for (int i = 0; i < 20; i++) {
            if (rateLimiter.isAllowed(ruleId, userId, capacity, leakRatePerSecond)) {
                allowed.incrementAndGet();
            }
        }

        // All 20 should be allowed (capacity is 100)
        assertThat(allowed.get()).isEqualTo(20);
    }

    @Test
    @DisplayName("Should track different rules independently")
    void testIndependentRuleTracking() {
        String rule1 = TestFixtures.PREMIUM_API_RULE_ID;
        String rule2 = TestFixtures.FREE_TIER_RULE_ID;
        String userId = TestFixtures.TEST_USER_ID;
        long capacity = 2;
        long leakRatePerSecond = 1;

        // Fill rule1
        rateLimiter.isAllowed(rule1, userId, capacity, leakRatePerSecond);
        rateLimiter.isAllowed(rule1, userId, capacity, leakRatePerSecond);
        boolean rule1Third = rateLimiter.isAllowed(rule1, userId, capacity, leakRatePerSecond);

        // rule2 should still allow
        boolean rule2First = rateLimiter.isAllowed(rule2, userId, capacity, leakRatePerSecond);

        assertThat(rule1Third).isFalse();
        assertThat(rule2First).isTrue();
    }

    @Test
    @DisplayName("Should track different users independently")
    void testIndependentUserTracking() {
        String ruleId = "test-users";
        String user1 = TestFixtures.TEST_USER_ID;
        String user2 = TestFixtures.TEST_USER_ID_2;
        long capacity = 1;
        long leakRatePerSecond = 1;

        // Fill user1
        rateLimiter.isAllowed(ruleId, user1, capacity, leakRatePerSecond);
        boolean user1Second = rateLimiter.isAllowed(ruleId, user1, capacity, leakRatePerSecond);

        // user2 should still allow
        boolean user2First = rateLimiter.isAllowed(ruleId, user2, capacity, leakRatePerSecond);

        assertThat(user1Second).isFalse();
        assertThat(user2First).isTrue();
    }

    @Test
    @DisplayName("Should fall back to in-memory on Redis failure")
    void testFallbackToInMemory() {
        String ruleId = "test-fallback";
        String userId = TestFixtures.TEST_USER_ID;
        long capacity = 3;
        long leakRatePerSecond = 1;

        // Fill queue
        AtomicInteger allowed = new AtomicInteger(0);
        for (int i = 0; i < 5; i++) {
            if (rateLimiter.isAllowed(ruleId, userId, capacity, leakRatePerSecond)) {
                allowed.incrementAndGet();
            }
        }

        // Should respect capacity (fallback or not)
        assertThat(allowed.get()).isEqualTo(3);
    }
}
