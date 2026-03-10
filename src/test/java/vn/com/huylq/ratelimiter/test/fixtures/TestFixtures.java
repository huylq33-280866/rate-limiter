package vn.com.huylq.ratelimiter.test.fixtures;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Central fixture provider for all tests.
 *
 * Provides:
 * - JWT token generation (valid, expired, unsigned, wrong algorithm)
 * - Rule definition fixtures (premium, free-tier, withdrawal, DDoS)
 * - RequestContext builders
 * - Sample constants and test data
 */
public class TestFixtures {

    // ==================== JWT Constants ====================

    public static final String JWT_SECRET = "test-secret-key-minimum-32-characters-long-for-hs256";
    public static final String WRONG_SECRET = "wrong-secret-key-minimum-32-characters-long-for-hs256";
    public static final String VALID_ALGORITHM = "HS256";
    public static final long TOKEN_EXPIRY_SECONDS = 3600;

    // ==================== Test User Constants ====================

    public static final String TEST_USER_ID = "user-123";
    public static final String TEST_USER_ID_2 = "user-456";
    public static final String TEST_PHONE_NUMBER = "233501234567";
    public static final String TEST_USERNAME = "john.doe";
    public static final String TEST_API_KEY = "api-key-xyz";

    // ==================== Test IP Constants ====================

    public static final String TEST_IP_ADDRESS = "203.0.113.45";
    public static final String TEST_IP_ADDRESS_2 = "203.0.113.99";
    public static final String TEST_INTERNAL_IP = "10.0.0.1";
    public static final String TEST_TRUSTED_PROXY_IP = "10.20.30.40";

    // ==================== Rule IDs ====================

    public static final String PREMIUM_API_RULE_ID = "premium-api-read";
    public static final String FREE_TIER_RULE_ID = "free-tier-api";
    public static final String WITHDRAWAL_RULE_ID = "withdraw-protection";
    public static final String DDOS_RULE_ID = "ddos-protection";

    // ==================== HTTP Methods & Endpoints ====================

    public static final String GET_METHOD = "GET";
    public static final String POST_METHOD = "POST";
    public static final String PUT_METHOD = "PUT";
    public static final String DELETE_METHOD = "DELETE";

    public static final String PREMIUM_API_ENDPOINT = "/api/v1/premium/orders";
    public static final String PUBLIC_API_ENDPOINT = "/api/v1/public/status";
    public static final String WITHDRAWAL_ENDPOINT = "/banking/withdraw";
    public static final String ROOT_ENDPOINT = "/";

    // ==================== JWT Token Generation ====================

    /**
     * Generate a valid JWT token with standard claims
     */
    public static String generateValidToken(String userId) {
        return generateValidToken(userId, System.currentTimeMillis() + (TOKEN_EXPIRY_SECONDS * 1000));
    }

    /**
     * Generate a valid JWT token with custom expiration
     */
    public static String generateValidToken(String userId, long expirationTimeMs) {
        return Jwts.builder()
            .setSubject(userId)
            .claim("user_id", userId)
            .claim("phone_number", TEST_PHONE_NUMBER)
            .claim("username", TEST_USERNAME)
            .setIssuedAt(new Date())
            .setExpiration(new Date(expirationTimeMs))
            .signWith(SignatureAlgorithm.HS256, JWT_SECRET)
            .compact();
    }

    /**
     * Generate a token with priority-1 field: user_id
     */
    public static String generateTokenWithUserId(String userId) {
        return Jwts.builder()
            .setSubject(userId)
            .claim("user_id", userId)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + (TOKEN_EXPIRY_SECONDS * 1000)))
            .signWith(SignatureAlgorithm.HS256, JWT_SECRET)
            .compact();
    }

    /**
     * Generate a token with priority-2 field: phone_number
     */
    public static String generateTokenWithPhoneNumber(String phoneNumber) {
        return Jwts.builder()
            .setSubject("mobile-user")
            .claim("phone_number", phoneNumber)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + (TOKEN_EXPIRY_SECONDS * 1000)))
            .signWith(SignatureAlgorithm.HS256, JWT_SECRET)
            .compact();
    }

    /**
     * Generate a token with priority-3 field: username
     */
    public static String generateTokenWithUsername(String username) {
        return Jwts.builder()
            .setSubject("web-user")
            .claim("username", username)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + (TOKEN_EXPIRY_SECONDS * 1000)))
            .signWith(SignatureAlgorithm.HS256, JWT_SECRET)
            .compact();
    }

    /**
     * Generate an expired JWT token
     */
    public static String generateExpiredToken(String userId) {
        return Jwts.builder()
            .setSubject(userId)
            .claim("user_id", userId)
            .setIssuedAt(new Date(System.currentTimeMillis() - (TOKEN_EXPIRY_SECONDS * 1000)))
            .setExpiration(new Date(System.currentTimeMillis() - 1000)) // Expired 1 second ago
            .signWith(SignatureAlgorithm.HS256, JWT_SECRET)
            .compact();
    }

    /**
     * Generate a JWT token without a signature
     */
    public static String generateUnsignedToken(String userId) {
        return Jwts.builder()
            .setSubject(userId)
            .claim("user_id", userId)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + (TOKEN_EXPIRY_SECONDS * 1000)))
            .compact();
    }

    /**
     * Generate a JWT token signed with wrong secret
     */
    public static String generateWrongSecretToken(String userId) {
        return Jwts.builder()
            .setSubject(userId)
            .claim("user_id", userId)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + (TOKEN_EXPIRY_SECONDS * 1000)))
            .signWith(SignatureAlgorithm.HS256, WRONG_SECRET)
            .compact();
    }

    /**
     * Generate a token with alg=none (security risk)
     */
    public static String generateNoneAlgorithmToken(String userId) {
        // Note: jjwt doesn't allow creating alg=none tokens directly for security reasons
        // This is a simulated case - in real attack, attacker would craft this manually
        return generateUnsignedToken(userId);
    }

    // ==================== Rule Definition Fixtures ====================

    /**
     * Premium API rule fixture: 1000 requests per hour per user
     */
    public static Map<String, Object> premiumApiRuleFixture() {
        return createRuleDefinition(
            PREMIUM_API_RULE_ID,
            GET_METHOD,
            "/api/v1/premium/*",
            1000,
            3600,
            "token-bucket"
        );
    }

    /**
     * Free tier rule fixture: 100 requests per hour per user
     */
    public static Map<String, Object> freeTierRuleFixture() {
        return createRuleDefinition(
            FREE_TIER_RULE_ID,
            "*",
            "/api/v1/public/*",
            100,
            3600,
            "sliding-window-counter"
        );
    }

    /**
     * Withdrawal rule fixture: 10 requests per day per IP
     */
    public static Map<String, Object> withdrawalRuleFixture() {
        return createRuleDefinition(
            WITHDRAWAL_RULE_ID,
            POST_METHOD,
            "/banking/withdraw",
            10,
            86400,
            "fixed-window-counter"
        );
    }

    /**
     * DDoS protection rule: 50000 requests per minute per IP
     */
    public static Map<String, Object> ddosRuleFixture() {
        return createRuleDefinition(
            DDOS_RULE_ID,
            "*",
            "*",
            50000,
            60,
            "sliding-window-log"
        );
    }

    private static Map<String, Object> createRuleDefinition(
            String id,
            String method,
            String endpoint,
            long limit,
            long windowSeconds,
            String algorithm) {
        Map<String, Object> rule = new HashMap<>();
        rule.put("id", id);
        rule.put("enabled", true);
        rule.put("method", method);
        rule.put("endpoint", endpoint);
        rule.put("requestsPerWindow", limit);
        rule.put("timeWindowSeconds", windowSeconds);
        rule.put("algorithm", algorithm);
        rule.put("priority", 1);
        return rule;
    }

    // ==================== RequestContext Builders ====================

    /**
     * Fluent builder for creating RequestContext instances
     */
    public static class RequestContextBuilder {
        private String userId = TEST_USER_ID;
        private String clientIp = TEST_IP_ADDRESS;
        private String httpMethod = GET_METHOD;
        private String endpoint = PREMIUM_API_ENDPOINT;

        public RequestContextBuilder withUserId(String userId) {
            this.userId = userId;
            return this;
        }

        public RequestContextBuilder withClientIp(String clientIp) {
            this.clientIp = clientIp;
            return this;
        }

        public RequestContextBuilder withHttpMethod(String httpMethod) {
            this.httpMethod = httpMethod;
            return this;
        }

        public RequestContextBuilder withEndpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public RequestContextBuilder asPremiumUser() {
            this.userId = TEST_USER_ID;
            this.httpMethod = GET_METHOD;
            this.endpoint = PREMIUM_API_ENDPOINT;
            return this;
        }

        public RequestContextBuilder asFreeTierUser() {
            this.userId = TEST_USER_ID;
            this.httpMethod = GET_METHOD;
            this.endpoint = PUBLIC_API_ENDPOINT;
            return this;
        }

        public RequestContextBuilder asWithdrawalAttempt() {
            this.userId = TEST_USER_ID;
            this.httpMethod = POST_METHOD;
            this.endpoint = WITHDRAWAL_ENDPOINT;
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> context = new HashMap<>();
            context.put("userId", userId);
            context.put("clientIp", clientIp);
            context.put("httpMethod", httpMethod);
            context.put("endpoint", endpoint);
            return context;
        }
    }

    /**
     * Create a new RequestContext builder
     */
    public static RequestContextBuilder requestContext() {
        return new RequestContextBuilder();
    }

    // ==================== Sample Token Payloads ====================

    /**
     * Sample token payload with all three identifier types
     */
    public static Map<String, Object> sampleTokenPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sub", TEST_USER_ID);
        payload.put("user_id", TEST_USER_ID);
        payload.put("phone_number", TEST_PHONE_NUMBER);
        payload.put("username", TEST_USERNAME);
        payload.put("iat", System.currentTimeMillis() / 1000);
        payload.put("exp", (System.currentTimeMillis() / 1000) + TOKEN_EXPIRY_SECONDS);
        return payload;
    }

    // ==================== Test Rules YAML Content ====================

    /**
     * Returns complete test rules YAML content for integration tests
     */
    public static String testRulesYaml() {
        return "rules:" + System.lineSeparator() +
            "  - id: premium-api-read" + System.lineSeparator() +
            "    enabled: true" + System.lineSeparator() +
            "    description: \"Premium users read endpoints\"" + System.lineSeparator() +
            "    conditions:" + System.lineSeparator() +
            "      method: \"GET\"" + System.lineSeparator() +
            "      endpoint: \"/api/v1/premium/*\"" + System.lineSeparator() +
            "    algorithm: \"token-bucket\"" + System.lineSeparator() +
            "    limits:" + System.lineSeparator() +
            "      requestsPerWindow: 1000" + System.lineSeparator() +
            "      timeWindowSeconds: 3600" + System.lineSeparator() +
            "    keyComponents:" + System.lineSeparator() +
            "      - \"userId\"" + System.lineSeparator() +
            "    priority: 1" + System.lineSeparator() +
            System.lineSeparator() +
            "  - id: free-tier-api" + System.lineSeparator() +
            "    enabled: true" + System.lineSeparator() +
            "    description: \"Free tier - all endpoints\"" + System.lineSeparator() +
            "    conditions:" + System.lineSeparator() +
            "      method: \"*\"" + System.lineSeparator() +
            "      endpoint: \"/api/v1/public/*\"" + System.lineSeparator() +
            "    algorithm: \"sliding-window-counter\"" + System.lineSeparator() +
            "    limits:" + System.lineSeparator() +
            "      requestsPerWindow: 100" + System.lineSeparator() +
            "      timeWindowSeconds: 3600" + System.lineSeparator() +
            "    keyComponents:" + System.lineSeparator() +
            "      - \"userId\"" + System.lineSeparator() +
            "    priority: 10" + System.lineSeparator() +
            System.lineSeparator() +
            "  - id: withdraw-protection" + System.lineSeparator() +
            "    enabled: true" + System.lineSeparator() +
            "    description: \"Prevent rapid withdrawals from same IP\"" + System.lineSeparator() +
            "    conditions:" + System.lineSeparator() +
            "      method: \"POST\"" + System.lineSeparator() +
            "      endpoint: \"/banking/withdraw\"" + System.lineSeparator() +
            "    algorithm: \"fixed-window-counter\"" + System.lineSeparator() +
            "    limits:" + System.lineSeparator() +
            "      requestsPerWindow: 10" + System.lineSeparator() +
            "      timeWindowSeconds: 86400" + System.lineSeparator() +
            "    keyComponents:" + System.lineSeparator() +
            "      - \"ip\"" + System.lineSeparator() +
            "    priority: 5" + System.lineSeparator() +
            System.lineSeparator() +
            "  - id: ddos-protection" + System.lineSeparator() +
            "    enabled: true" + System.lineSeparator() +
            "    description: \"Global rate limit per IP\"" + System.lineSeparator() +
            "    conditions:" + System.lineSeparator() +
            "      method: \"*\"" + System.lineSeparator() +
            "      endpoint: \"*\"" + System.lineSeparator() +
            "    algorithm: \"sliding-window-log\"" + System.lineSeparator() +
            "    limits:" + System.lineSeparator() +
            "      requestsPerWindow: 50000" + System.lineSeparator() +
            "      timeWindowSeconds: 60" + System.lineSeparator() +
            "    keyComponents:" + System.lineSeparator() +
            "      - \"ip\"" + System.lineSeparator() +
            "    priority: 100" + System.lineSeparator();
    }

    // ==================== Test Constants ====================

    /**
     * All test user identifiers
     */
    public static String[] allTestUsers() {
        return new String[]{TEST_USER_ID, TEST_USER_ID_2, "phone:" + TEST_PHONE_NUMBER, "user:" + TEST_USERNAME};
    }

    /**
     * All test IPs
     */
    public static String[] allTestIps() {
        return new String[]{TEST_IP_ADDRESS, TEST_IP_ADDRESS_2, TEST_INTERNAL_IP};
    }

    /**
     * All test rule IDs
     */
    public static String[] allTestRuleIds() {
        return new String[]{PREMIUM_API_RULE_ID, FREE_TIER_RULE_ID, WITHDRAWAL_RULE_ID, DDOS_RULE_ID};
    }

    /**
     * All HTTP methods tested
     */
    public static String[] allHttpMethods() {
        return new String[]{GET_METHOD, POST_METHOD, PUT_METHOD, DELETE_METHOD};
    }
}
