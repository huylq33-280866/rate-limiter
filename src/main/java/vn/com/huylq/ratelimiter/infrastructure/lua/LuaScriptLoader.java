package vn.com.huylq.ratelimiter.infrastructure.lua;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lua Script Loader - Loads and caches scripts at startup
 *
 * Loads Lua scripts from classpath (resources/lua/) at startup via @PostConstruct
 * and caches content in a ConcurrentHashMap for thread-safe access.
 *
 * Note: Bean is created by RateLimiterAutoConfiguration, not auto-discovered.
 */
@Slf4j
public class LuaScriptLoader {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ConcurrentHashMap<String, String> scriptContentCache = new ConcurrentHashMap<>();

    // Script names to load
    private static final String[] SCRIPTS_TO_LOAD = {
        "token-bucket",
        "leaking-bucket",
        "fixed-window-counter",
        "sliding-window-log",
        "sliding-window-counter"
    };

    // ============ CONSTRUCTOR ============

    public LuaScriptLoader(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
        log.info("✓ LuaScriptLoader initialized");
    }

    // ============ INITIALIZATION ============

    /**
     * Load all Lua scripts at startup and cache SHA values
     * Called automatically by Spring after construction (@PostConstruct)
     */
    @PostConstruct
    public void loadAllScripts() {
        log.info("Loading Lua scripts from resources...");

        for (String scriptName : SCRIPTS_TO_LOAD) {
            try {
                String scriptContent = loadScriptFromFile(scriptName);
                scriptContentCache.put(scriptName, scriptContent);

                log.info("✓ Loaded script '{}'", scriptName);
            } catch (Exception e) {
                log.error("✗ Failed to load script '{}': {}", scriptName, e.getMessage(), e);
                throw new RuntimeException("Failed to load Lua script: " + scriptName, e);
            }
        }

        log.info("✓ All {} Lua scripts loaded successfully", SCRIPTS_TO_LOAD.length);
    }

    // ============ PUBLIC INTERFACE ============

    /**
     * Get script content (for EVAL fallback when EVALSHA fails)
     *
     * @param scriptName script name
     * @return full script content
     * @throws IllegalArgumentException if script not found
     */
    public String getScriptContent(String scriptName) {
        String content = scriptContentCache.get(scriptName);
        if (content == null) {
            throw new IllegalArgumentException("Script not found: " + scriptName);
        }
        return content;
    }

    /**
     * Check if script is loaded
     *
     * @param scriptName script name
     * @return true if script loaded
     */
    public boolean hasScript(String scriptName) {
        return scriptContentCache.containsKey(scriptName);
    }

    // ============ PRIVATE METHODS ============

    /**
     * Load script file from resources directory
     *
     * Location: src/main/resources/lua/{scriptName}.lua
     *
     * @param scriptName script name (without .lua extension)
     * @return script content as string
     * @throws IOException if file not found or read fails
     */
    private String loadScriptFromFile(String scriptName) throws IOException {
        String filename = scriptName + ".lua";

        // Try to load from classpath resource
        try {
            String resourcePath = "lua/" + filename;
            java.io.InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath);
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Failed to load script from classpath: {}", filename);
            throw e;
        }
    }

}
