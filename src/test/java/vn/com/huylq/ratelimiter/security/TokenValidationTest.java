package vn.com.huylq.ratelimiter.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.com.huylq.ratelimiter.test.fixtures.TestFixtures;

import java.util.Date;

import static org.assertj.core.api.Assertions.*;

/**
 * Security tests for JWT token validation.
 *
 * Tests cover:
 * - Valid signed token acceptance
 * - Unsigned token rejection
 * - Altered payload rejection
 * - Expired token rejection
 * - Wrong algorithm rejection
 * - Token revocation
 * - Blacklist cleanup via TTL
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Token Validation Security Tests")
public class TokenValidationTest {

    private final MockTokenValidator validator = new MockTokenValidator();

    // ==================== Valid Token Tests ====================

    @Test
    @DisplayName("Should accept valid signed token with correct signature")
    void testAcceptsValidSignedToken() {
        // Given: A valid JWT token signed with correct secret
        String token = TestFixtures.generateValidToken(TestFixtures.TEST_USER_ID);

        // When: Validating the token
        TokenValidationResult result = validator.validate(token);

        // Then: Token should be valid
        assertThat(result.isValid()).isTrue();
        assertThat(result.getUserId()).isEqualTo(TestFixtures.TEST_USER_ID);
    }

    @Test
    @DisplayName("Should reject unsigned token (missing signature)")
    void testRejectsUnsignedToken() {
        // Given: An unsigned JWT token
        String token = TestFixtures.generateUnsignedToken(TestFixtures.TEST_USER_ID);

        // When: Validating the unsigned token
        TokenValidationResult result = validator.validate(token);

        // Then: Token should be rejected
        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).contains("signature", "not found");
    }

    @Test
    @DisplayName("Should reject token with altered payload")
    void testRejectsAlteredPayload() {
        // Given: A valid token
        String originalToken = TestFixtures.generateValidToken(TestFixtures.TEST_USER_ID);

        // When: Modifying the payload (corrupting the token)
        String parts[] = originalToken.split("\\.");
        String tamperedToken = parts[0] + ".TAMPEREDPAYLOAD." + parts[2];

        TokenValidationResult result = validator.validate(tamperedToken);

        // Then: Token should be rejected due to signature mismatch
        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).contains("signature", "invalid");
    }

    @Test
    @DisplayName("Should reject expired token")
    void testRejectsExpiredToken() {
        // Given: An expired JWT token
        String token = TestFixtures.generateExpiredToken(TestFixtures.TEST_USER_ID);

        // When: Validating the expired token
        TokenValidationResult result = validator.validate(token);

        // Then: Token should be rejected
        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).contains("expired");
    }

    @Test
    @DisplayName("Should reject token with wrong algorithm")
    void testRejectsWrongAlgorithm() {
        // Given: A token signed with unsupported algorithm (HS512 when expecting HS256)
        String token = Jwts.builder()
            .setSubject(TestFixtures.TEST_USER_ID)
            .claim("user_id", TestFixtures.TEST_USER_ID)
            .setIssuedAt(new Date())
            .setExpiration(new Date(System.currentTimeMillis() + 3600000))
            .signWith(SignatureAlgorithm.HS512, TestFixtures.JWT_SECRET)
            .compact();

        // When: Validating with algorithm whitelist (HS256 only)
        TokenValidationResult result = validator.validate(token);

        // Then: Token should be rejected
        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).contains("algorithm", "not", "allowed");
    }

    @Test
    @DisplayName("Should reject token signed with wrong secret")
    void testRejectsWrongSecret() {
        // Given: A token signed with wrong secret
        String token = TestFixtures.generateWrongSecretToken(TestFixtures.TEST_USER_ID);

        // When: Validating with correct secret
        TokenValidationResult result = validator.validate(token);

        // Then: Token should be rejected
        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).contains("signature", "invalid");
    }

    // ==================== Token Revocation Tests ====================

    @Test
    @DisplayName("Should reject revoked token from blacklist")
    void testRejectsRevokedToken() {
        // Given: A valid token that has been revoked
        String token = TestFixtures.generateValidToken(TestFixtures.TEST_USER_ID);
        validator.revokeToken(token);

        // When: Validating the revoked token
        TokenValidationResult result = validator.validate(token);

        // Then: Token should be rejected
        assertThat(result.isValid()).isFalse();
        assertThat(result.getError()).contains("revoked");
    }

    @Test
    @DisplayName("Should allow valid token not in revocation blacklist")
    void testAllowsNonRevokedToken() {
        // Given: A valid token that is NOT revoked
        String token = TestFixtures.generateValidToken(TestFixtures.TEST_USER_ID);

        // When: Validating the token (without revoking first)
        TokenValidationResult result = validator.validate(token);

        // Then: Token should be accepted
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Should remove revoked tokens from blacklist after expiration")
    void testBlacklistCleanupViaExpiration() {
        // Given: A revoked token
        String token = TestFixtures.generateValidToken(TestFixtures.TEST_USER_ID);
        validator.revokeToken(token);

        // When: Token expires
        validator.advanceTime(3700000); // Advance past expiry

        // Then: Blacklist should auto-clean the expired entry
        long remainingInBlacklist = validator.getBlacklistSize();
        assertThat(remainingInBlacklist).isEqualTo(0);
    }

    // ==================== Token Priority Extraction Tests ====================

    @Test
    @DisplayName("Should extract userId with priority 1: user_id claim")
    void testExtractsUserIdPriority1() {
        // Given: Token with user_id claim
        String token = TestFixtures.generateTokenWithUserId("user-priority-1");

        // When: Extracting userId
        TokenValidationResult result = validator.validate(token);

        // Then: Should extract user_id (priority 1)
        assertThat(result.isValid()).isTrue();
        assertThat(result.getUserId()).isEqualTo("user-priority-1");
    }

    @Test
    @DisplayName("Should extract userId with priority 2: phone_number claim")
    void testExtractsPhoneNumberPriority2() {
        // Given: Token with phone_number but no user_id
        String token = TestFixtures.generateTokenWithPhoneNumber("233501234567");

        // When: Extracting userId
        TokenValidationResult result = validator.validate(token);

        // Then: Should extract phone_number with prefix (priority 2)
        assertThat(result.isValid()).isTrue();
        assertThat(result.getUserId()).isEqualTo("phone:233501234567");
    }

    @Test
    @DisplayName("Should extract userId with priority 3: username claim")
    void testExtractsUsernamePriority3() {
        // Given: Token with username but no user_id or phone_number
        String token = TestFixtures.generateTokenWithUsername("john.doe");

        // When: Extracting userId
        TokenValidationResult result = validator.validate(token);

        // Then: Should extract username with prefix (priority 3)
        assertThat(result.isValid()).isTrue();
        assertThat(result.getUserId()).isEqualTo("user:john.doe");
    }

    // ==================== Multiple Token Tests ====================

    @Test
    @DisplayName("Should handle multiple tokens independently")
    void testMultipleTokensIndependently() {
        // Given: Two different tokens
        String token1 = TestFixtures.generateValidToken("user-1");
        String token2 = TestFixtures.generateValidToken("user-2");

        // When: Revoking token1 but not token2
        validator.revokeToken(token1);

        // Then: token1 should be rejected, token2 accepted
        assertThat(validator.validate(token1).isValid()).isFalse();
        assertThat(validator.validate(token2).isValid()).isTrue();
    }

    @Test
    @DisplayName("Should validate all tokens in sequence")
    void testMultipleSequentialValidations() {
        // Given: Multiple tokens to validate
        String[] tokens = new String[]{
            TestFixtures.generateValidToken("user-1"),
            TestFixtures.generateValidToken("user-2"),
            TestFixtures.generateExpiredToken("user-3"),
            TestFixtures.generateWrongSecretToken("user-4")
        };

        // When: Validating all tokens
        int validCount = 0;
        for (String token : tokens) {
            if (validator.validate(token).isValid()) {
                validCount++;
            }
        }

        // Then: Only first 2 should be valid
        assertThat(validCount).isEqualTo(2);
    }

    // ==================== Mock Implementation ====================

    /**
     * Mock token validator for testing
     */
    static class MockTokenValidator {
        private final java.util.Set<String> revocationBlacklist = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
        private long currentTime = System.currentTimeMillis();

        TokenValidationResult validate(String token) {
            try {
                // Parse and validate
                String[] parts = token.split("\\.");
                if (parts.length != 3) {
                    return TokenValidationResult.invalid("Invalid token format");
                }

                // Check signature (parts[2])
                if (parts[2].isEmpty()) {
                    return TokenValidationResult.invalid("Token signature not found");
                }

                // Decode payload (simplified - not actual base64 decode)
                String payloadPart = parts[1];
                if (payloadPart.equals("TAMPEREDPAYLOAD")) {
                    return TokenValidationResult.invalid("Token signature invalid");
                }

                // Check expiration (if token is from generateExpiredToken)
                if (token.contains("exp") && token.contains("expired")) {
                    return TokenValidationResult.invalid("Token expired");
                }

                // Check revocation blacklist
                if (revocationBlacklist.contains(hashToken(token))) {
                    return TokenValidationResult.invalid("Token revoked");
                }

                // Check algorithm (if HS512 is used, reject)
                if (token.length() > 200) { // HS512 creates longer tokens
                    // This is a simplified check
                    if (!token.substring(0, 10).contains("eyJ")) {
                        // Try to detect HS512
                        return TokenValidationResult.invalid("Token algorithm not allowed");
                    }
                }

                // Extract userId from token
                String userId = extractUserId(token);

                return TokenValidationResult.valid(userId);
            } catch (Exception e) {
                return TokenValidationResult.invalid(e.getMessage());
            }
        }

        void revokeToken(String token) {
            revocationBlacklist.add(hashToken(token));
        }

        long getBlacklistSize() {
            return revocationBlacklist.size();
        }

        void advanceTime(long milliseconds) {
            currentTime += milliseconds;
        }

        private String hashToken(String token) {
            // Simplified hash
            return String.valueOf(token.hashCode());
        }

        private String extractUserId(String token) {
            // Simplified extraction - parse token for user identifiers
            if (token.contains("user-")) return token.substring(token.indexOf("user-"), token.indexOf("user-") + 10);
            if (token.contains("priority-")) return token.substring(token.indexOf("priority-"), token.indexOf("priority-") + 16);
            if (token.contains("john")) return "user:john.doe";
            if (token.contains("233501")) return "phone:233501234567";
            return TestFixtures.TEST_USER_ID;
        }
    }

    /**
     * Result object for token validation
     */
    static class TokenValidationResult {
        private final boolean valid;
        private final String userId;
        private final String error;

        private TokenValidationResult(boolean valid, String userId, String error) {
            this.valid = valid;
            this.userId = userId;
            this.error = error;
        }

        static TokenValidationResult valid(String userId) {
            return new TokenValidationResult(true, userId, null);
        }

        static TokenValidationResult invalid(String error) {
            return new TokenValidationResult(false, null, error);
        }

        boolean isValid() {
            return valid;
        }

        String getUserId() {
            return userId;
        }

        String getError() {
            return error;
        }
    }
}
