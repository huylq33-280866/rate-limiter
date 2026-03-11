package vn.com.huylq.ratelimiter.infrastructure.lua;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lua Script Loader - Loads and caches scripts at startup
 *
 * Responsibilities:
 * - Load Lua scripts from src/main/resources/lua/ directory
 * - Calculate SHA1 hash for each script (used for EVALSHA optimization)
 * - Cache script content and SHAs in memory
 * - Provide getSha() and getScriptContent() methods
 * - Log all loaded scripts
 *
 * Strategy:
 * - Load all scripts once at startup (@PostConstruct)
 * - Cache in ConcurrentHashMap for thread-safe access
 * - SHA values used for EVALSHA Redis command (80% bandwidth reduction)
 *
 * Note: Bean is created by RateLimiterAutoConfiguration, not auto-discovered.
 */
@Slf4j
public class LuaScriptLoader {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ConcurrentHashMap<String, String> scriptShaCache = new ConcurrentHashMap<>();
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
                String sha = calculateSha1(scriptContent);

                scriptContentCache.put(scriptName, scriptContent);
                scriptShaCache.put(scriptName, sha);

                log.info("✓ Loaded script '{}' - SHA: {}", scriptName, sha);
            } catch (Exception e) {
                log.error("✗ Failed to load script '{}': {}", scriptName, e.getMessage(), e);
                throw new RuntimeException("Failed to load Lua script: " + scriptName, e);
            }
        }

        log.info("✓ All {} Lua scripts loaded successfully", SCRIPTS_TO_LOAD.length);
    }

    // ============ PUBLIC INTERFACE ============

    /**
     * Get SHA1 hash for a script (for EVALSHA command)
     *
     * @param scriptName script name (e.g., "tokenBucket" -> "token-bucket.lua")
     * @return SHA1 hash of script content
     * @throws IllegalArgumentException if script not found
     */
    public String getSha(String scriptName) {
        String sha = scriptShaCache.get(scriptName);
        if (sha == null) {
            throw new IllegalArgumentException("Script not found: " + scriptName);
        }
        return sha;
    }

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
        return scriptShaCache.containsKey(scriptName);
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

    /**
     * Calculate SHA1 hash of script content
     * Redis EVALSHA uses SHA1 to identify scripts
     *
     * @param scriptContent script content
     * @return SHA1 hash (40 character hex string)
     * @throws NoSuchAlgorithmException if SHA1 not available
     */
    private String calculateSha1(String scriptContent) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] messageDigest = md.digest(scriptContent.getBytes(StandardCharsets.UTF_8));

        // Convert to hex string
        StringBuilder sb = new StringBuilder();
        for (byte b : messageDigest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
