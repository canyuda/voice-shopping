package com.voiceshopping.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Properties;

/**
 * Optional startup check that fails fast when Redis isn't configured to emit
 * keyspace notifications for expirations. The next iteration's
 * {@code SessionExpireListener} relies on {@code notify-keyspace-events=Ex} —
 * silent absence of those events is far worse than a loud startup failure.
 * <p>
 * Disabled by default ({@code voice-shopping.memory.keyspace-notification.check-on-startup=false}).
 * Turn this on once a session-expire listener is wired in.
 */
@Component
@ConditionalOnProperty(
        prefix = "voice-shopping.memory.keyspace-notification",
        name = "check-on-startup",
        havingValue = "true")
public class KeyspaceNotificationStartupChecker implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(KeyspaceNotificationStartupChecker.class);
    private static final String CONFIG_KEY = "notify-keyspace-events";

    private final RedisConnectionFactory connectionFactory;

    public KeyspaceNotificationStartupChecker(RedisConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * Hidden via {@code @ConditionalOnProperty}. When enabled, hits Redis
     * {@code CONFIG GET notify-keyspace-events} and verifies the value contains
     * both {@code 'E'} (keyevent channel) and {@code 'x'} (expired events).
     */
    @Override
    public void afterPropertiesSet() {
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();

        String value = template.execute((RedisCallback<String>) connection -> {
            Properties props = connection.serverCommands().getConfig(CONFIG_KEY);
            return props == null ? null : props.getProperty(CONFIG_KEY);
        });

        if (value == null || !value.contains("E") || !value.contains("x")) {
            String msg = "Redis notify-keyspace-events 未启用 'Ex'（当前值='" + value
                    + "'），请配置 notify-keyspace-events=Ex 后再启动。";
            log.error(msg);
            throw new BeanInitializationException(msg);
        }
        log.info("Redis keyspace notification check passed: notify-keyspace-events='{}'", value);
    }
}
