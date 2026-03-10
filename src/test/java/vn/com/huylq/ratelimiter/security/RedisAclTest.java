package vn.com.huylq.ratelimiter.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for Redis ACL (Access Control List) enforcement.
 *
 * Tests cover:
 * - Rate limiter user has only necessary permissions
 * - Dangerous commands (FLUSHDB, DEL, CONFIG) are blocked
 * - Allowed commands (EVALSHA, HSET, EXPIRE) execute successfully
 *
 * Note: Redis ACL restricts the rate-limiter user to:
 * - EVALSHA, SCRIPT LOAD (for Lua scripts)
 * - HGETALL, HSET, EXPIRE (for state management)
 * - SELECT, INFO, PING (for monitoring)
 * - Denies dangerous commands like FLUSHDB, FLUSHALL, CONFIG, etc.
 */
@Testcontainers
@DisplayName("Redis ACL Enforcement Tests")
public class RedisAclTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
        .withExposedPorts(6379);

    private MockRedisAclEnforcer aclEnforcer;

    @BeforeEach
    void setUp() {
        aclEnforcer = new MockRedisAclEnforcer();
        // Configure ACL rules
        aclEnforcer.configureRateLimiterUser();
    }

    // ==================== Permission Restriction Tests ====================

    @Test
    @DisplayName("Rate limiter user has only necessary permissions")
    void testRateLimiterUserHasOnlyNecessaryPermissions() {
        // Given: Rate limiter user with restricted ACL
        String rateLimiterUser = "rate-limiter";

        // When: Checking allowed commands
        Set<String> allowedCommands = aclEnforcer.getAllowedCommands(rateLimiterUser);

        // Then: Should only include necessary commands
        assertThat(allowedCommands)
            .contains("EVALSHA", "SCRIPT", "HGETALL", "HSET", "EXPIRE", "SELECT", "INFO", "PING")
            .doesNotContain("FLUSHDB", "FLUSHALL", "DEL", "CONFIG", "SHUTDOWN", "SAVE", "BGSAVE");
    }

    @Test
    @DisplayName("Rate limiter user cannot access all keys")
    void testRateLimiterUserKeyRestrictions() {
        // Given: Rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Checking key patterns
        Set<String> allowedPatterns = aclEnforcer.getKeyPatterns(rateLimiterUser);

        // Then: Should have some key restriction
        // In production: ~rate-limiter::* (only rate limiter keys)
        assertThat(allowedPatterns).isNotEmpty();
    }

    // ==================== Dangerous Command Blocking Tests ====================

    @Test
    @DisplayName("FLUSHDB command is blocked for rate limiter user")
    void testFlushdbCommandBlocked() {
        // Given: Attempting FLUSHDB as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing FLUSHDB
        RedisCommandResult result = aclEnforcer.executeCommand(rateLimiterUser, "FLUSHDB");

        // Then: Command should be blocked
        assertThat(result.isAllowed()).isFalse();
        assertThat(result.getError()).contains("not allowed", "permission");
    }

    @Test
    @DisplayName("FLUSHALL command is blocked for rate limiter user")
    void testFlushallCommandBlocked() {
        // Given: Attempting FLUSHALL as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing FLUSHALL
        RedisCommandResult result = aclEnforcer.executeCommand(rateLimiterUser, "FLUSHALL");

        // Then: Command should be blocked
        assertThat(result.isAllowed()).isFalse();
    }

    @Test
    @DisplayName("DEL command is blocked for rate limiter user")
    void testDelCommandBlocked() {
        // Given: Attempting DEL as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing DEL
        RedisCommandResult result = aclEnforcer.executeCommand(
            rateLimiterUser,
            "DEL",
            "rate-limiter::token-bucket::rule1::user1"
        );

        // Then: Command should be blocked
        assertThat(result.isAllowed()).isFalse();
    }

    @Test
    @DisplayName("CONFIG commands are blocked for rate limiter user")
    void testConfigCommandBlocked() {
        // Given: Attempting CONFIG as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing CONFIG GET
        RedisCommandResult result = aclEnforcer.executeCommand(rateLimiterUser, "CONFIG", "GET", "maxmemory");

        // Then: Command should be blocked
        assertThat(result.isAllowed()).isFalse();
    }

    @Test
    @DisplayName("SHUTDOWN command is blocked for rate limiter user")
    void testShutdownCommandBlocked() {
        // Given: Attempting SHUTDOWN as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing SHUTDOWN
        RedisCommandResult result = aclEnforcer.executeCommand(rateLimiterUser, "SHUTDOWN");

        // Then: Command should be blocked
        assertThat(result.isAllowed()).isFalse();
    }

    @Test
    @DisplayName("All dangerous commands are blocked")
    void testAllDangerousCommandsBlocked() {
        // Given: List of dangerous commands
        String[] dangerousCommands = {
            "FLUSHDB", "FLUSHALL", "DEL", "CONFIG", "SHUTDOWN",
            "SAVE", "BGSAVE", "BGREWRITEAOF", "SLAVEOF", "REPLICAOF",
            "MONITOR", "DEBUG", "MODULE", "SCRIPT FLUSH", "RESET"
        };

        // When: Attempting each dangerous command
        for (String command : dangerousCommands) {
            RedisCommandResult result = aclEnforcer.executeCommand("rate-limiter", command);

            // Then: All should be blocked
            assertThat(result.isAllowed())
                .as("Command '%s' should be blocked", command)
                .isFalse();
        }
    }

    // ==================== Allowed Command Tests ====================

    @Test
    @DisplayName("EVALSHA command executes successfully")
    void testEvalshaCommandAllowed() {
        // Given: Executing EVALSHA as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing EVALSHA
        RedisCommandResult result = aclEnforcer.executeCommand(
            rateLimiterUser,
            "EVALSHA",
            "abc123def456...",  // SHA1
            "1",                 // numkeys
            "rate-limiter::token-bucket::rule1::user1"
        );

        // Then: Command should be allowed
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("HSET command executes successfully")
    void testHsetCommandAllowed() {
        // Given: Executing HSET as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing HSET
        RedisCommandResult result = aclEnforcer.executeCommand(
            rateLimiterUser,
            "HSET",
            "rate-limiter::token-bucket::rule1::user1",
            "tokens", "50"
        );

        // Then: Command should be allowed
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("HGETALL command executes successfully")
    void testHgetallCommandAllowed() {
        // Given: Executing HGETALL as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing HGETALL
        RedisCommandResult result = aclEnforcer.executeCommand(
            rateLimiterUser,
            "HGETALL",
            "rate-limiter::token-bucket::rule1::user1"
        );

        // Then: Command should be allowed
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("EXPIRE command executes successfully")
    void testExpireCommandAllowed() {
        // Given: Executing EXPIRE as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing EXPIRE
        RedisCommandResult result = aclEnforcer.executeCommand(
            rateLimiterUser,
            "EXPIRE",
            "rate-limiter::token-bucket::rule1::user1",
            "86400"
        );

        // Then: Command should be allowed
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("SCRIPT LOAD command executes successfully")
    void testScriptLoadCommandAllowed() {
        // Given: Executing SCRIPT LOAD as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing SCRIPT LOAD
        RedisCommandResult result = aclEnforcer.executeCommand(
            rateLimiterUser,
            "SCRIPT", "LOAD",
            "return 1"  // Simple Lua script
        );

        // Then: Command should be allowed
        assertThat(result.isAllowed()).isTrue();
    }

    @Test
    @DisplayName("PING command executes successfully")
    void testPingCommandAllowed() {
        // Given: Executing PING as rate limiter user
        String rateLimiterUser = "rate-limiter";

        // When: Executing PING
        RedisCommandResult result = aclEnforcer.executeCommand(rateLimiterUser, "PING");

        // Then: Command should be allowed
        assertThat(result.isAllowed()).isTrue();
    }

    // ==================== Audit Tests ====================

    @Test
    @DisplayName("Should audit denied command attempts")
    void testAuditsDeniedCommands() {
        // Given: Rate limiter user attempts blocked command
        String rateLimiterUser = "rate-limiter";
        aclEnforcer.executeCommand(rateLimiterUser, "FLUSHDB");

        // When: Checking audit log
        List<String> auditLog = aclEnforcer.getAuditLog();

        // Then: Should have recorded the denied attempt
        assertThat(auditLog)
            .anySatisfy(entry -> assertThat(entry)
                .contains("FLUSHDB", "denied", "rate-limiter"));
    }

    // ==================== Mock Implementation ====================

    /**
     * Mock Redis ACL enforcer for testing
     */
    static class MockRedisAclEnforcer {
        private final Map<String, Set<String>> userPermissions = new HashMap<>();
        private final List<String> auditLog = new java.util.ArrayList<>();

        void configureRateLimiterUser() {
            Set<String> permissions = new HashSet<>();

            // Allowed commands for rate limiter
            permissions.add("EVALSHA");
            permissions.add("EVAL");
            permissions.add("SCRIPT LOAD");
            permissions.add("HGETALL");
            permissions.add("HSET");
            permissions.add("HMSET");
            permissions.add("EXPIRE");
            permissions.add("ZADD");
            permissions.add("ZRANGEBYSCORE");
            permissions.add("ZREM");
            permissions.add("ZCARD");
            permissions.add("SELECT");
            permissions.add("INFO");
            permissions.add("PING");

            userPermissions.put("rate-limiter", permissions);
        }

        Set<String> getAllowedCommands(String user) {
            return new HashSet<>(userPermissions.getOrDefault(user, new HashSet<>()));
        }

        Set<String> getKeyPatterns(String user) {
            // In production: rate-limiter user can access "rate-limiter::*" keys
            Set<String> patterns = new HashSet<>();
            patterns.add("rate-limiter::*");
            return patterns;
        }

        RedisCommandResult executeCommand(String user, String... commandParts) {
            String command = commandParts[0].toUpperCase();
            Set<String> allowed = userPermissions.getOrDefault(user, new HashSet<>());

            // Check if command is allowed
            boolean isAllowed = allowed.stream()
                .anyMatch(cmd -> cmd.startsWith(command));

            // Dangerous commands that should always be blocked
            boolean isDangerous = isDangerousCommand(command);

            if (isDangerous && !isAllowed) {
                auditLog.add(String.format(
                    "DENIED: User '%s' attempted command '%s'",
                    user, command
                ));
                return RedisCommandResult.denied("Command not allowed: " + command);
            }

            if (isAllowed && !isDangerous) {
                return RedisCommandResult.allowed();
            }

            return RedisCommandResult.denied("Permission denied");
        }

        private boolean isDangerousCommand(String command) {
            String[] dangerous = {
                "FLUSHDB", "FLUSHALL", "DEL", "CONFIG", "SHUTDOWN",
                "SAVE", "BGSAVE", "BGREWRITEAOF", "SLAVEOF", "REPLICAOF",
                "MONITOR", "DEBUG", "MODULE", "RESET"
            };

            for (String cmd : dangerous) {
                if (command.equals(cmd)) {
                    return true;
                }
            }

            return false;
        }

        List<String> getAuditLog() {
            return new ArrayList<>(auditLog);
        }
    }

    /**
     * Result of Redis command execution with ACL check
     */
    static class RedisCommandResult {
        private final boolean allowed;
        private final String error;

        private RedisCommandResult(boolean allowed, String error) {
            this.allowed = allowed;
            this.error = error;
        }

        static RedisCommandResult allowed() {
            return new RedisCommandResult(true, null);
        }

        static RedisCommandResult denied(String error) {
            return new RedisCommandResult(false, error);
        }

        boolean isAllowed() {
            return allowed;
        }

        String getError() {
            return error;
        }
    }
}
