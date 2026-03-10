package vn.com.huylq.ratelimiter.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis Configuration
 *
 * Configures RedisTemplate for use by rate limiter.
 * Redisson Spring Boot starter auto-configures the connection factory.
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * Create RedisTemplate for string operations
     *
     * This template is used by:
     * - LuaScriptLoader
     * - LuaScriptExecutor
     * - TokenBucketRateLimiter
     * - InMemoryRateLimiter fallback
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        log.info("Creating RedisTemplate bean");

        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // Set string serializer for keys and values
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        template.afterPropertiesSet();

        log.info("✓ RedisTemplate configured");
        return template;
    }
}
