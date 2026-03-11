package vn.com.huylq.ratelimiter.config;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import redis.embedded.RedisServer;

/**
 * Configuration for embedded Redis in tests
 *
 * Starts an embedded Redis server on port 6379 for testing.
 * Automatically started when @ActiveProfiles("test") is used.
 */
@Configuration
@ConditionalOnProperty(
    name = "embedded.redis.enabled",
    havingValue = "true",
    matchIfMissing = false
)
public class EmbeddedRedisConfig {

    private RedisServer redisServer;

    public EmbeddedRedisConfig() {
        // Port 6379 - default Redis port
        this.redisServer = new RedisServer(6379);
    }

    @PostConstruct
    public void startRedis() throws Exception {
        redisServer.start();
        System.out.println("✓ Embedded Redis started on port 6379");
    }

    @PreDestroy
    public void stopRedis() {
        if (redisServer != null) {
            redisServer.stop();
            System.out.println("✓ Embedded Redis stopped");
        }
    }
}
