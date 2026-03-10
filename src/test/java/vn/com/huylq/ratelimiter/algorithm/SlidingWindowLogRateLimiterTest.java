package vn.com.huylq.ratelimiter.algorithm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import vn.com.huylq.ratelimiter.domain.algorithm.SlidingWindowLogRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.fallback.InMemoryRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.lua.LuaScriptExecutor;
import vn.com.huylq.ratelimiter.test.fixtures.TestFixtures;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("SlidingWindowLog Rate Limiter Tests")
public class SlidingWindowLogRateLimiterTest {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private LuaScriptExecutor luaScriptExecutor;

    @Autowired
    private InMemoryRateLimiter inMemoryFallback;

    private SlidingWindowLogRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new SlidingWindowLogRateLimiter(redisTemplate, luaScriptExecutor, inMemoryFallback);
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    @DisplayName("Should allow requests within window limit")
    void testAllowsRequestsWithinLimit() {
        String ruleId = TestFixtures.PREMIUM_API_RULE_ID;
        String userId = TestFixtures.TEST_USER_ID;
        long limit = 5;
        long windowSeconds = 3600;

        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.isAllowed(ruleId, userId, limit, windowSeconds)).isTrue();
        }
    }

    @Test
    @DisplayName("Should reject requests exceeding window limit")
    void testRejectsRequestsExceedingLimit() {
        String ruleId = TestFixtures.PREMIUM_API_RULE_ID;
        String userId = TestFixtures.TEST_USER_ID;
        long limit = 2;
        long windowSeconds = 3600;

        assertThat(rateLimiter.isAllowed(ruleId, userId, limit, windowSeconds)).isTrue();
        assertThat(rateLimiter.isAllowed(ruleId, userId, limit, windowSeconds)).isTrue();
        assertThat(rateLimiter.isAllowed(ruleId, userId, limit, windowSeconds)).isFalse();
    }

    @Test
    @DisplayName("Should remove old entries after window expires")
    void testRemovesOldEntriesAfterWindow() {
        String ruleId = "test-window";
        String userId = TestFixtures.TEST_USER_ID;
        long limit = 2;
        long windowSeconds = 1;

        // Fill window
        assertThat(rateLimiter.isAllowed(ruleId, userId, limit, windowSeconds)).isTrue();
        assertThat(rateLimiter.isAllowed(ruleId, userId, limit, windowSeconds)).isTrue();
        assertThat(rateLimiter.isAllowed(ruleId, userId, limit, windowSeconds)).isFalse();

        // Wait for window to expire
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Should be allowed after old entries removed
        assertThat(rateLimiter.isAllowed(ruleId, userId, limit, windowSeconds)).isTrue();
    }

    @Test
    @DisplayName("Should track different rules independently")
    void testIndependentRuleTracking() {
        String rule1 = TestFixtures.PREMIUM_API_RULE_ID;
        String rule2 = TestFixtures.FREE_TIER_RULE_ID;
        String userId = TestFixtures.TEST_USER_ID;
        long limit = 1;
        long windowSeconds = 3600;

        rateLimiter.isAllowed(rule1, userId, limit, windowSeconds);
        assertThat(rateLimiter.isAllowed(rule1, userId, limit, windowSeconds)).isFalse();
        assertThat(rateLimiter.isAllowed(rule2, userId, limit, windowSeconds)).isTrue();
    }

    @Test
    @DisplayName("Should track different users independently")
    void testIndependentUserTracking() {
        String ruleId = "test-users";
        String user1 = TestFixtures.TEST_USER_ID;
        String user2 = TestFixtures.TEST_USER_ID_2;
        long limit = 1;
        long windowSeconds = 3600;

        rateLimiter.isAllowed(ruleId, user1, limit, windowSeconds);
        assertThat(rateLimiter.isAllowed(ruleId, user1, limit, windowSeconds)).isFalse();
        assertThat(rateLimiter.isAllowed(ruleId, user2, limit, windowSeconds)).isTrue();
    }
}
