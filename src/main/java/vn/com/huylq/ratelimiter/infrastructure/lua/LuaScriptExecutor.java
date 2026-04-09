package vn.com.huylq.ratelimiter.infrastructure.lua;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Executes Lua scripts in Redis via Spring's DefaultRedisScript,
 * which handles EVALSHA/EVAL fallback internally.
 *
 * Note: Bean is created by RateLimiterAutoConfiguration, not auto-discovered.
 */
@Slf4j
public class LuaScriptExecutor {

    private final RedisTemplate<String, Object> redisTemplate;
    private final LuaScriptLoader scriptLoader;

    // ============ CONSTRUCTOR ============

    public LuaScriptExecutor(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptLoader scriptLoader) {

        this.redisTemplate = redisTemplate;
        this.scriptLoader = scriptLoader;

        log.info("✓ LuaScriptExecutor initialized");
    }

    // ============ PUBLIC INTERFACE ============

    /**
     * Execute Lua script by name with EVALSHA optimization
     *
     * @param scriptName script identifier (e.g., "token-bucket")
     * @param keys KEYS array for script
     * @param args ARGV array for script
     * @return script return value (Long)
     * @throws Exception if execution fails
     */
    public Long executeLuaScript(String scriptName, List<String> keys, List<String> args) {
        String scriptContent = scriptLoader.getScriptContent(scriptName);
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(scriptContent, Long.class);
        Object result = redisTemplate.execute(script, keys, args.toArray());
        return extractReturnValue(result);
    }

    /**
     * Reload scripts after Redis restart
     *
     * Called when script SHA cache miss detected
     */
    public void reloadScripts() {
        log.info("Reloading Lua scripts...");
        scriptLoader.loadAllScripts();
        log.info("✓ Scripts reloaded successfully");
    }

    // ============ PRIVATE METHODS ============

    /**
     * Extract script return value with type safety
     * Handles Long, Integer, String, and null returns
     */
    private Long extractReturnValue(Object result) {
        if (result == null) {
            return 0L;
        }

        if (result instanceof Long) {
            return (Long) result;
        }

        if (result instanceof Integer) {
            return ((Integer) result).longValue();
        }

        if (result instanceof String) {
            try {
                return Long.parseLong((String) result);
            } catch (NumberFormatException e) {
                log.error("Cannot parse script result as Long: {}", result);
                return 0L;
            }
        }

        log.warn("Unexpected script return type: {}", result.getClass().getName());
        return 0L;
    }

}
