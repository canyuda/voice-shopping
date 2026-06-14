package com.voiceshopping.business.session;

import com.voiceshopping.business.memory.LongTermMemoryWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Wires the {@link SessionExpireListener} + its {@link RedisMessageListenerContainer}.
 * <p>
 * Toggled by {@code voice-shopping.memory.session-expire-listener.enabled} — default
 * {@code false} so applications without {@code notify-keyspace-events=Ex} don't fail
 * silently. Pair with the keyspace-notification startup check to surface
 * misconfiguration as an explicit BeanInitializationException at boot.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "voice-shopping.memory.session-expire-listener",
        name = "enabled",
        havingValue = "true")
public class SessionExpireListenerConfig {

    private static final Logger log = LoggerFactory.getLogger(SessionExpireListenerConfig.class);

    /**
     * Dedicated container for keyspace-event subscriptions. Kept private to this
     * config so other listeners pick up their own container if they ever appear —
     * no cross-listener interference on shutdown.
     */
    @Bean
    public RedisMessageListenerContainer sessionExpireListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        log.info("SessionExpireListener container initialized");
        return container;
    }

    @Bean
    public SessionExpireListener sessionExpireListener(
            RedisMessageListenerContainer sessionExpireListenerContainer,
            SessionService sessionService,
            LongTermMemoryWriter longTermMemoryWriter) {
        SessionExpireListener listener = new SessionExpireListener(
                sessionExpireListenerContainer, sessionService, longTermMemoryWriter);
        log.info("SessionExpireListener registered for vs:session:* expirations");
        return listener;
    }
}
