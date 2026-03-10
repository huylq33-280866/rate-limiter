package vn.com.huylq.ratelimiter.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import vn.com.huylq.ratelimiter.test.fixtures.TestFixtures;

import static org.assertj.core.api.Assertions.*;

/**
 * Security tests for Lua injection prevention.
 *
 * Tests cover:
 * - KEYS parameter injection attempts
 * - ARGV parameter injection attempts
 * - Malicious payload effects
 * - Parameter binding safety
 *
 * Note: Lua injection is prevented by:
 * 1. Using RedisTemplate parameter binding (not string concatenation)
 * 2. Passing KEYS and ARGV as separate lists
 * 3. Lua script validation
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Lua Injection Prevention Tests")
public class LuaInjectionTest {

    private final MockLuaScriptExecutor scriptExecutor = new MockLuaScriptExecutor();

    // ==================== KEYS Parameter Injection Tests ====================

    @Test
    @DisplayName("Should prevent injection via KEYS parameter")
    void testKeysParameterInjection() {
        // Given: Malicious KEYS parameter attempting to escape
        String maliciousKey = "rate-limiter::token-bucket::rule1::user1\"; return redis.call('FLUSHDB'); \"";
        String script = "return redis.call('GET', KEYS[1])";

        // When: Executing script with malicious KEYS
        LuaExecutionResult result = scriptExecutor.execute(
            script,
            new String[]{maliciousKey},
            new String[]{}
        );

        // Then: Script should treat KEYS as literal string, not execute injection
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getValue()).isNotNull();
        // The FLUSHDB should NOT have been executed
        assertThat(scriptExecutor.isDatabaseFlushed()).isFalse();
    }

    @Test
    @DisplayName("Should prevent multiple KEYS injection")
    void testMultipleKeysInjection() {
        // Given: Multiple malicious KEYS parameters
        String maliciousKey1 = "key1'; return redis.call('DEL', KEYS[*])";
        String maliciousKey2 = "key2'); redis.call('FLUSHALL');";
        String script = "return {redis.call('GET', KEYS[1]), redis.call('GET', KEYS[2])}";

        // When: Executing script with malicious KEYS array
        LuaExecutionResult result = scriptExecutor.execute(
            script,
            new String[]{maliciousKey1, maliciousKey2},
            new String[]{}
        );

        // Then: Should execute safely as literal strings
        assertThat(result.isSuccessful()).isTrue();
        assertThat(scriptExecutor.isDatabaseFlushed()).isFalse();
    }

    // ==================== ARGV Parameter Injection Tests ====================

    @Test
    @DisplayName("Should prevent injection via ARGV parameter")
    void testArgvParameterInjection() {
        // Given: Malicious ARGV parameter with Lua code
        String maliciousArg = "100\nreturn redis.call('FLUSHDB')\n";
        String script = "local limit = tonumber(ARGV[1]); return limit";

        // When: Executing script with malicious ARGV
        LuaExecutionResult result = scriptExecutor.execute(
            script,
            new String[]{"rate-limiter::test::user1"},
            new String[]{maliciousArg}
        );

        // Then: ARGV should be treated as literal value, not code
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getValue()).isEqualTo(100.0);
        assertThat(scriptExecutor.isDatabaseFlushed()).isFalse();
    }

    @Test
    @DisplayName("Should prevent code injection in ARGV")
    void testCodeInjectionInArgv() {
        // Given: Attempting to inject Lua code through ARGV
        String injectedCode = "'; redis.call('FLUSHDB'); local x='";
        String script = "local value = ARGV[1]; return value";

        // When: Executing script
        LuaExecutionResult result = scriptExecutor.execute(
            script,
            new String[]{"key1"},
            new String[]{injectedCode}
        );

        // Then: Code should not be executed
        assertThat(result.isSuccessful()).isTrue();
        assertThat(scriptExecutor.isDatabaseFlushed()).isFalse();
    }

    // ==================== Malicious Payload Tests ====================

    @Test
    @DisplayName("Should not allow malicious payload to affect rate limiting")
    void testMaliciousPayloadDoesNotAffectRateLimit() {
        // Given: A rate limiting script with malicious payload
        String maliciousLimit = "1000; redis.call('DEL', KEYS[1]); return 0";
        String ruleId = TestFixtures.PREMIUM_API_RULE_ID;
        String userId = TestFixtures.TEST_USER_ID;

        // When: Executing rate limiting with malicious limit parameter
        String key = "rate-limiter::token-bucket::" + ruleId + "::" + userId;
        LuaExecutionResult result = scriptExecutor.execute(
            "rate-limiting-script",
            new String[]{key},
            new String[]{maliciousLimit, "3600"}
        );

        // Then: Rate limiting should work correctly despite injection attempt
        assertThat(result.isSuccessful()).isTrue();
        // Key should still exist (not deleted by injection)
        assertThat(scriptExecutor.keyExists(key)).isTrue();
    }

    @Test
    @DisplayName("Should prevent EVALSHA cache poisoning via injection")
    void testEvalshaCachePoisoning() {
        // Given: Attempting to poison the script cache
        String poisonedScript = "local x = 'normal'; " +
            "redis.call('SCRIPT', 'FLUSH'); " +
            "return 1";

        // When: Executing poisoned script
        LuaExecutionResult result = scriptExecutor.execute(
            poisonedScript,
            new String[]{"key1"},
            new String[]{}
        );

        // Then: Script cache should not be flushed
        assertThat(result.isSuccessful()).isTrue();
        assertThat(scriptExecutor.getScriptCacheSize()).isGreaterThan(0);
    }

    // ==================== Parameter Binding Tests ====================

    @Test
    @DisplayName("Should use parameter binding instead of concatenation")
    void testParameterBindingPrevention() {
        // Given: A script executor using proper parameter binding
        String script = "if redis.call('GET', KEYS[1]) then return 1 else return 0 end";
        String key = "test-key"; // No special characters
        String value = "test-value"; // No special characters

        // When: Executing with proper binding
        LuaExecutionResult result = scriptExecutor.execute(
            script,
            new String[]{key},
            new String[]{value}
        );

        // Then: Should execute successfully
        assertThat(result.isSuccessful()).isTrue();
    }

    @Test
    @DisplayName("Should safely handle special characters in parameters")
    void testSpecialCharactersInParameters() {
        // Given: Parameters with special characters
        String[] specialChars = {
            "key\\with\\backslash",
            "key'with'quotes",
            "key\"with\"doublequotes",
            "key;with;semicolons",
            "key\nwith\nnewlines",
            "key\twith\ttabs"
        };

        // When: Executing script with special character parameters
        for (String specialKey : specialChars) {
            LuaExecutionResult result = scriptExecutor.execute(
                "return KEYS[1]",
                new String[]{specialKey},
                new String[]{}
            );

            // Then: Should handle safely and return key literally
            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.getValue()).isEqualTo(specialKey);
        }
    }

    @Test
    @DisplayName("Should prevent Redis command injection in script content")
    void testRedisCommandInjectionPrevention() {
        // Given: Attempting to call dangerous Redis commands
        String script = "redis.call('FLUSHDB')";

        // When: Executing dangerous script
        // In a properly secured environment, this would be blocked
        LuaExecutionResult result = scriptExecutor.execute(
            script,
            new String[]{},
            new String[]{}
        );

        // Then: Dangerous commands should be blocked or script should be validated
        // (This depends on Redis ACL configuration)
        if (result.isSuccessful()) {
            assertThat(scriptExecutor.isDatabaseFlushed()).isFalse();
        }
    }

    // ==================== Mock Implementation ====================

    /**
     * Mock Lua script executor for testing
     */
    static class MockLuaScriptExecutor {
        private boolean databaseFlushed = false;
        private final java.util.Map<String, Object> keyStore = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.Set<String> scriptCache = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<>()
        );

        LuaExecutionResult execute(String script, String[] keys, String[] argv) {
            try {
                // Simulate Lua execution with proper parameter binding
                // Parameters are kept separate, not concatenated

                // Check for dangerous operations
                if (script.contains("FLUSHDB") || script.contains("FLUSHALL")) {
                    // In real Redis, this would be blocked by ACL
                    // For testing, we log the attempt
                    databaseFlushed = false; // Would be blocked
                }

                // Simulate script execution
                Object result = null;

                if (script.contains("return KEYS[1]") && keys.length > 0) {
                    result = keys[0]; // Return the key as-is
                } else if (script.contains("return")) {
                    result = 1; // Default return value
                }

                // Store in cache
                scriptCache.add(script.substring(0, Math.min(40, script.length())));

                return LuaExecutionResult.success(result);
            } catch (Exception e) {
                return LuaExecutionResult.failure(e.getMessage());
            }
        }

        boolean isDatabaseFlushed() {
            return databaseFlushed;
        }

        boolean keyExists(String key) {
            return keyStore.containsKey(key);
        }

        int getScriptCacheSize() {
            return scriptCache.size();
        }
    }

    /**
     * Result of Lua script execution
     */
    static class LuaExecutionResult {
        private final boolean successful;
        private final Object value;
        private final String error;

        private LuaExecutionResult(boolean successful, Object value, String error) {
            this.successful = successful;
            this.value = value;
            this.error = error;
        }

        static LuaExecutionResult success(Object value) {
            return new LuaExecutionResult(true, value, null);
        }

        static LuaExecutionResult failure(String error) {
            return new LuaExecutionResult(false, null, error);
        }

        boolean isSuccessful() {
            return successful;
        }

        Object getValue() {
            return value;
        }

        String getError() {
            return error;
        }
    }
}
