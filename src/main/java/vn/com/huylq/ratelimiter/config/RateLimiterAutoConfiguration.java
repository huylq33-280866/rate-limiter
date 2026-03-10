package vn.com.huylq.ratelimiter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import vn.com.huylq.ratelimiter.domain.algorithm.FixedWindowCounterRateLimiter;
import vn.com.huylq.ratelimiter.domain.algorithm.LeakingBucketRateLimiter;
import vn.com.huylq.ratelimiter.domain.algorithm.SlidingWindowCounterRateLimiter;
import vn.com.huylq.ratelimiter.domain.algorithm.SlidingWindowLogRateLimiter;
import vn.com.huylq.ratelimiter.domain.algorithm.TokenBucketRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.fallback.InMemoryRateLimiter;
import vn.com.huylq.ratelimiter.infrastructure.lua.LuaScriptExecutor;
import vn.com.huylq.ratelimiter.infrastructure.lua.LuaScriptLoader;

/**
 * Rate Limiter Auto Configuration
 *
 * Wires all beans for the rate limiting system in correct dependency order:
 * 1. RedisTemplate (provided by Spring Boot auto-config)
 * 2. LuaScriptLoader (loads scripts at startup)
 * 3. LuaScriptExecutor (executes scripts)
 * 4. InMemoryRateLimiter (fallback)
 * 5. TokenBucketRateLimiter (main implementation)
 *
 * Order is critical - components must be created in dependency order.
 */
@Slf4j
@Configuration
public class RateLimiterAutoConfiguration {

    /**
     * Create LuaScriptLoader bean
     *
     * Loads all Lua scripts from resources/lua/ at startup.
     * @PostConstruct method loads scripts and caches SHAs.
     */
    @Bean
    public LuaScriptLoader luaScriptLoader(RedisTemplate<String, Object> redisTemplate) {
        log.info("Creating LuaScriptLoader bean");
        return new LuaScriptLoader(redisTemplate);
    }

    /**
     * Create LuaScriptExecutor bean
     *
     * Executes Lua scripts with EVALSHA optimization.
     * Depends on LuaScriptLoader for cached SHAs.
     */
    @Bean
    public LuaScriptExecutor luaScriptExecutor(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptLoader scriptLoader) {
        log.info("Creating LuaScriptExecutor bean");
        return new LuaScriptExecutor(redisTemplate, scriptLoader);
    }

    /**
     * Create InMemoryRateLimiter bean (fallback)
     *
     * Provides fallback when Redis is unavailable.
     * Thread-safe in-memory implementation of token bucket.
     */
    @Bean
    public InMemoryRateLimiter inMemoryRateLimiter() {
        log.info("Creating InMemoryRateLimiter bean (fallback)");
        return new InMemoryRateLimiter();
    }

    /**
     * Create TokenBucketRateLimiter bean
     *
     * Main rate limiter implementation using Redis + Lua scripts.
     * Falls back to InMemoryRateLimiter on Redis failure.
     */
    @Bean
    public TokenBucketRateLimiter tokenBucketRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptExecutor luaScriptExecutor,
            InMemoryRateLimiter inMemoryFallback) {
        log.info("Creating TokenBucketRateLimiter bean");
        return new TokenBucketRateLimiter(redisTemplate, luaScriptExecutor, inMemoryFallback);
    }

    /**
     * Create LeakingBucketRateLimiter bean
     *
     * Leaking bucket implementation - fixed outflow rate, no bursts.
     * Falls back to InMemoryRateLimiter on Redis failure.
     */
    @Bean
    public LeakingBucketRateLimiter leakingBucketRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptExecutor luaScriptExecutor,
            InMemoryRateLimiter inMemoryFallback) {
        log.info("Creating LeakingBucketRateLimiter bean");
        return new LeakingBucketRateLimiter(redisTemplate, luaScriptExecutor, inMemoryFallback);
    }

    /**
     * Create FixedWindowCounterRateLimiter bean
     *
     * Simple fixed-window counter - requests per time window, resets every window.
     */
    @Bean
    public FixedWindowCounterRateLimiter fixedWindowCounterRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptExecutor luaScriptExecutor,
            InMemoryRateLimiter inMemoryFallback) {
        log.info("Creating FixedWindowCounterRateLimiter bean");
        return new FixedWindowCounterRateLimiter(redisTemplate, luaScriptExecutor, inMemoryFallback);
    }

    /**
     * Create SlidingWindowLogRateLimiter bean
     *
     * Precise sliding window using timestamp log in sorted set.
     * No boundary issues, perfect accuracy.
     */
    @Bean
    public SlidingWindowLogRateLimiter slidingWindowLogRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptExecutor luaScriptExecutor,
            InMemoryRateLimiter inMemoryFallback) {
        log.info("Creating SlidingWindowLogRateLimiter bean");
        return new SlidingWindowLogRateLimiter(redisTemplate, luaScriptExecutor, inMemoryFallback);
    }

    /**
     * Create SlidingWindowCounterRateLimiter bean
     *
     * Sliding window with sub-window counters.
     * Good accuracy, balanced memory usage.
     */
    @Bean
    public SlidingWindowCounterRateLimiter slidingWindowCounterRateLimiter(
            RedisTemplate<String, Object> redisTemplate,
            LuaScriptExecutor luaScriptExecutor,
            InMemoryRateLimiter inMemoryFallback) {
        log.info("Creating SlidingWindowCounterRateLimiter bean");
        return new SlidingWindowCounterRateLimiter(redisTemplate, luaScriptExecutor, inMemoryFallback);
    }
}
