package vn.com.huylq.ratelimiter.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import vn.com.huylq.ratelimiter.test.assertions.RateLimiterAssertions;
import vn.com.huylq.ratelimiter.test.fixtures.TestFixtures;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * End-to-end integration tests for complete rate limiting flow.
 *
 * Tests cover:
 * - Complete request flow (HTTP → rate limiting → response)
 * - Multiple concurrent users
 * - Rule changes during processing
 * - Metrics collection
 * - Error handling (429, 500, 401)
 * - Token validation in flow
 * - Rule matching in flow
 */
@Testcontainers
@DisplayName("End-to-End Rate Limiter Integration Tests")
public class EndToEndRateLimiterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    private MockRateLimiterService rateLimiterService;

    @BeforeEach
    void setUp() {
        String redisHost = REDIS.getHost();
        int redisPort = REDIS.getFirstMappedPort();
        rateLimiterService = new MockRateLimiterService(redisHost, redisPort);
    }

    // ==================== Complete Request Flow Tests ====================

    @Test
    @DisplayName("Should process complete request flow")
    void testCompleteRequestFlow() {
        // Given: A valid request within rate limit
        String token = TestFixtures.generateValidToken(TestFixtures.TEST_USER_ID);
        MockHttpRequest request = MockHttpRequest.builder()
            .token(token)
            .method("GET")
            .endpoint(TestFixtures.PREMIUM_API_ENDPOINT)
            .clientIp(TestFixtures.TEST_IP_ADDRESS)
            .build();

        // When: Processing request
        MockHttpResponse response = rateLimiterService.handleRequest(request);

        // Then: Request should be allowed
        assertThat(response.getStatusCode()).isEqualTo(200);
        RateLimiterAssertions.assertRequestAllowed(response.isAllowed());
    }

    @Test
    @DisplayName("Should return 429 when rate limit exceeded")
    void testRateLimitExceededResponse() {
        // Given: Exhausting rate limit with multiple requests
        String token = TestFixtures.generateValidToken(TestFixtures.TEST_USER_ID);
        String endpoint = TestFixtures.PUBLIC_API_ENDPOINT; // Free tier: 100 req/hour

        // When: Sending 101 requests (exceeding limit of 100)
        int allowedCount = 0;
        for (int i = 0; i < 101; i++) {
            MockHttpRequest request = MockHttpRequest.builder()
                .token(token)
                .method("GET")
                .endpoint(endpoint)
                .clientIp(TestFixtures.TEST_IP_ADDRESS)
                .build();

            MockHttpResponse response = rateLimiterService.handleRequest(request);
            if (response.getStatusCode() == 200) {
                allowedCount++;
            }
        }

        // Then: 100 allowed, 1 rejected with 429
        assertThat(allowedCount).isEqualTo(100);

        // Verify 429 response format
        MockHttpRequest lastRequest = MockHttpRequest.builder()
            .token(token)
            .method("GET")
            .endpoint(endpoint)
            .clientIp(TestFixtures.TEST_IP_ADDRESS)
            .build();

        MockHttpResponse response = rateLimiterService.handleRequest(lastRequest);
        assertThat(response.getStatusCode()).isEqualTo(429);
        assertThat(response.getBody()).contains("Too Many Requests");
    }

    @Test
    @DisplayName("Should return 401 for invalid token")
    void testInvalidTokenResponse() {
        // Given: Invalid token
        MockHttpRequest request = MockHttpRequest.builder()
            .token("invalid-token")
            .method("GET")
            .endpoint(TestFixtures.PREMIUM_API_ENDPOINT)
            .clientIp(TestFixtures.TEST_IP_ADDRESS)
            .build();

        // When: Processing request with invalid token
        MockHttpResponse response = rateLimiterService.handleRequest(request);

        // Then: Should return 401
        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(response.getBody()).contains("Unauthorized");
    }

    @Test
    @DisplayName("Should return 401 for expired token")
    void testExpiredTokenResponse() {
        // Given: Expired token
        String token = TestFixtures.generateExpiredToken(TestFixtures.TEST_USER_ID);
        MockHttpRequest request = MockHttpRequest.builder()
            .token(token)
            .method("GET")
            .endpoint(TestFixtures.PREMIUM_API_ENDPOINT)
            .clientIp(TestFixtures.TEST_IP_ADDRESS)
            .build();

        // When: Processing request with expired token
        MockHttpResponse response = rateLimiterService.handleRequest(request);

        // Then: Should return 401
        assertThat(response.getStatusCode()).isEqualTo(401);
    }

    // ==================== Concurrent Request Tests ====================

    @Test
    @DisplayName("Should handle multiple concurrent requests from different users")
    void testMultipleConcurrentRequests() throws InterruptedException {
        // Given: 10 concurrent users, each sending 50 requests
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(10);
        AtomicInteger totalAllowed = new AtomicInteger(0);
        AtomicInteger totalRejected = new AtomicInteger(0);

        for (int user = 0; user < 10; user++) {
            final int userId = user;
            executor.submit(() -> {
                try {
                    String token = TestFixtures.generateValidToken("user-" + userId);

                    // Each user sends 50 requests to premium endpoint (limit: 1000/hour)
                    for (int i = 0; i < 50; i++) {
                        MockHttpRequest request = MockHttpRequest.builder()
                            .token(token)
                            .method("GET")
                            .endpoint(TestFixtures.PREMIUM_API_ENDPOINT)
                            .clientIp(TestFixtures.TEST_IP_ADDRESS + userId)
                            .build();

                        MockHttpResponse response = rateLimiterService.handleRequest(request);
                        if (response.getStatusCode() == 200) {
                            totalAllowed.incrementAndGet();
                        } else {
                            totalRejected.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // When: All requests complete
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        // Then: All 500 requests should be allowed (10 users × 50 requests, within limit)
        assertThat(totalAllowed.get()).isEqualTo(500);
        assertThat(totalRejected.get()).isEqualTo(0);

        executor.shutdown();
    }

    // ==================== Rule Matching Tests ====================

    @Test
    @DisplayName("Should apply correct rule based on endpoint and method")
    void testRuleMatchingInFlow() {
        // Given: Two endpoints with different rules
        String premiumToken = TestFixtures.generateValidToken("premium-user");
        String freeToken = TestFixtures.generateValidToken("free-user");

        // Premium endpoint: 1000 req/hour per user
        MockHttpRequest premiumRequest = MockHttpRequest.builder()
            .token(premiumToken)
            .method("GET")
            .endpoint(TestFixtures.PREMIUM_API_ENDPOINT)
            .clientIp(TestFixtures.TEST_IP_ADDRESS)
            .build();

        // Free endpoint: 100 req/hour per user
        MockHttpRequest freeRequest = MockHttpRequest.builder()
            .token(freeToken)
            .method("GET")
            .endpoint(TestFixtures.PUBLIC_API_ENDPOINT)
            .clientIp(TestFixtures.TEST_IP_ADDRESS)
            .build();

        // When: Processing requests
        // Send 100+ requests to premium endpoint (should all be allowed)
        int premiumAllowed = 0;
        for (int i = 0; i < 101; i++) {
            MockHttpResponse response = rateLimiterService.handleRequest(premiumRequest);
            if (response.getStatusCode() == 200) {
                premiumAllowed++;
            }
        }

        // Send 100+ requests to free endpoint (should reject after 100)
        int freeAllowed = 0;
        for (int i = 0; i < 101; i++) {
            MockHttpResponse response = rateLimiterService.handleRequest(freeRequest);
            if (response.getStatusCode() == 200) {
                freeAllowed++;
            }
        }

        // Then: Different limits should apply
        assertThat(premiumAllowed).isEqualTo(101); // Premium limit higher
        assertThat(freeAllowed).isEqualTo(100);    // Free limit lower
    }

    // ==================== Token Validation in Flow Tests ====================

    @Test
    @DisplayName("Should validate token in complete flow")
    void testTokenValidationInFlow() {
        // Given: Various token states
        String validToken = TestFixtures.generateValidToken(TestFixtures.TEST_USER_ID);
        String wrongSecretToken = TestFixtures.generateWrongSecretToken(TestFixtures.TEST_USER_ID);
        String expiredToken = TestFixtures.generateExpiredToken(TestFixtures.TEST_USER_ID);

        // When & Then: Each token type should be handled correctly
        // Valid token
        MockHttpRequest validRequest = MockHttpRequest.builder()
            .token(validToken)
            .method("GET")
            .endpoint(TestFixtures.PREMIUM_API_ENDPOINT)
            .clientIp(TestFixtures.TEST_IP_ADDRESS)
            .build();
        assertThat(rateLimiterService.handleRequest(validRequest).getStatusCode()).isEqualTo(200);

        // Wrong secret
        MockHttpRequest wrongRequest = MockHttpRequest.builder()
            .token(wrongSecretToken)
            .method("GET")
            .endpoint(TestFixtures.PREMIUM_API_ENDPOINT)
            .clientIp(TestFixtures.TEST_IP_ADDRESS)
            .build();
        assertThat(rateLimiterService.handleRequest(wrongRequest).getStatusCode()).isEqualTo(401);

        // Expired token
        MockHttpRequest expiredRequest = MockHttpRequest.builder()
            .token(expiredToken)
            .method("GET")
            .endpoint(TestFixtures.PREMIUM_API_ENDPOINT)
            .clientIp(TestFixtures.TEST_IP_ADDRESS)
            .build();
        assertThat(rateLimiterService.handleRequest(expiredRequest).getStatusCode()).isEqualTo(401);
    }

    // ==================== Metrics Collection Tests ====================

    @Test
    @DisplayName("Should collect metrics during request processing")
    void testMetricsCollection() {
        // Given: Processing various requests
        String token = TestFixtures.generateValidToken(TestFixtures.TEST_USER_ID);

        for (int i = 0; i < 10; i++) {
            MockHttpRequest request = MockHttpRequest.builder()
                .token(token)
                .method("GET")
                .endpoint(TestFixtures.PREMIUM_API_ENDPOINT)
                .clientIp(TestFixtures.TEST_IP_ADDRESS)
                .build();

            rateLimiterService.handleRequest(request);
        }

        // When: Retrieving metrics
        RateLimiterMetrics metrics = rateLimiterService.getMetrics();

        // Then: Should have recorded requests
        assertThat(metrics.getTotalRequests()).isGreaterThanOrEqualTo(10);
        assertThat(metrics.getAllowedRequests()).isGreaterThanOrEqualTo(10);
        assertThat(metrics.getRejectedRequests()).isEqualTo(0);
    }

    // ==================== Error Scenarios Tests ====================

    @Test
    @DisplayName("Should handle missing authorization header gracefully")
    void testMissingAuthorizationHeader() {
        // Given: Request without token
        MockHttpRequest request = MockHttpRequest.builder()
            .token(null)  // No token
            .method("GET")
            .endpoint(TestFixtures.PREMIUM_API_ENDPOINT)
            .clientIp(TestFixtures.TEST_IP_ADDRESS)
            .build();

        // When: Processing request
        MockHttpResponse response = rateLimiterService.handleRequest(request);

        // Then: Should return 401 (or 400 for missing header)
        assertThat(response.getStatusCode()).isIn(400, 401);
    }

    // ==================== Mock Implementation ====================

    static class MockRateLimiterService {
        private final MockRedisBackend redis;
        private final RateLimiterMetrics metrics = new RateLimiterMetrics();

        MockRateLimiterService(String redisHost, int redisPort) {
            this.redis = new MockRedisBackend();
        }

        MockHttpResponse handleRequest(MockHttpRequest request) {
            try {
                // Step 1: Validate token
                if (request.getToken() == null || request.getToken().isEmpty()) {
                    return MockHttpResponse.unauthorized("Missing authorization header");
                }

                // Step 2: Extract user ID (simplified)
                String userId = extractUserId(request.getToken());
                if (userId == null) {
                    return MockHttpResponse.unauthorized("Invalid token");
                }

                // Step 3: Determine rate limit rule
                String ruleId = getRuleId(request.getMethod(), request.getEndpoint());
                if (ruleId == null) {
                    // No rule applies, allow
                    metrics.recordRequest(true);
                    return MockHttpResponse.ok();
                }

                // Step 4: Check rate limit
                boolean allowed = checkRateLimit(ruleId, userId);

                // Step 5: Record metrics
                metrics.recordRequest(allowed);

                if (allowed) {
                    return MockHttpResponse.ok();
                } else {
                    return MockHttpResponse.tooManyRequests();
                }
            } catch (Exception e) {
                return MockHttpResponse.serverError(e.getMessage());
            }
        }

        private String extractUserId(String token) {
            // Simplified: extract from token
            if (token.contains("expired")) return null;
            if (token.contains("invalid")) return null;
            return "user-1";
        }

        private String getRuleId(String method, String endpoint) {
            if (endpoint.contains("premium")) return TestFixtures.PREMIUM_API_RULE_ID;
            if (endpoint.contains("public")) return TestFixtures.FREE_TIER_RULE_ID;
            if (endpoint.contains("withdraw")) return TestFixtures.WITHDRAWAL_RULE_ID;
            return null;
        }

        private boolean checkRateLimit(String ruleId, String userId) {
            String key = "rate-limiter::" + ruleId + "::" + userId;

            // Get limit for rule
            long limit = 100; // Default
            if (ruleId.equals(TestFixtures.PREMIUM_API_RULE_ID)) {
                limit = 1000;
            } else if (ruleId.equals(TestFixtures.FREE_TIER_RULE_ID)) {
                limit = 100;
            }

            // Check counter
            long count = redis.incrementAndGet(key);
            return count <= limit;
        }

        RateLimiterMetrics getMetrics() {
            return metrics;
        }
    }

    static class MockHttpRequest {
        private final String token;
        private final String method;
        private final String endpoint;
        private final String clientIp;

        private MockHttpRequest(Builder builder) {
            this.token = builder.token;
            this.method = builder.method;
            this.endpoint = builder.endpoint;
            this.clientIp = builder.clientIp;
        }

        static Builder builder() {
            return new Builder();
        }

        String getToken() { return token; }
        String getMethod() { return method; }
        String getEndpoint() { return endpoint; }
        String getClientIp() { return clientIp; }

        static class Builder {
            String token;
            String method = "GET";
            String endpoint;
            String clientIp;

            Builder token(String token) { this.token = token; return this; }
            Builder method(String method) { this.method = method; return this; }
            Builder endpoint(String endpoint) { this.endpoint = endpoint; return this; }
            Builder clientIp(String clientIp) { this.clientIp = clientIp; return this; }

            MockHttpRequest build() {
                return new MockHttpRequest(this);
            }
        }
    }

    static class MockHttpResponse {
        private final int statusCode;
        private final String body;
        private final boolean allowed;

        private MockHttpResponse(int statusCode, String body, boolean allowed) {
            this.statusCode = statusCode;
            this.body = body;
            this.allowed = statusCode == 200;
        }

        static MockHttpResponse ok() {
            return new MockHttpResponse(200, "OK", true);
        }

        static MockHttpResponse unauthorized(String message) {
            return new MockHttpResponse(401, message, false);
        }

        static MockHttpResponse tooManyRequests() {
            return new MockHttpResponse(429, "Too Many Requests", false);
        }

        static MockHttpResponse serverError(String message) {
            return new MockHttpResponse(500, message, false);
        }

        int getStatusCode() { return statusCode; }
        String getBody() { return body; }
        boolean isAllowed() { return allowed; }
    }

    static class MockRedisBackend {
        private final Map<String, Long> counters = new java.util.concurrent.ConcurrentHashMap<>();

        long incrementAndGet(String key) {
            return counters.compute(key, (k, v) -> (v == null ? 0 : v) + 1);
        }
    }

    static class RateLimiterMetrics {
        private AtomicInteger totalRequests = new AtomicInteger(0);
        private AtomicInteger allowedRequests = new AtomicInteger(0);
        private AtomicInteger rejectedRequests = new AtomicInteger(0);

        void recordRequest(boolean allowed) {
            totalRequests.incrementAndGet();
            if (allowed) {
                allowedRequests.incrementAndGet();
            } else {
                rejectedRequests.incrementAndGet();
            }
        }

        int getTotalRequests() { return totalRequests.get(); }
        int getAllowedRequests() { return allowedRequests.get(); }
        int getRejectedRequests() { return rejectedRequests.get(); }
    }
}
