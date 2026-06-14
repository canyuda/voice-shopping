package com.voiceshopping.business.session;

import com.voiceshopping.business.memory.LongTermMemoryWriter;
import com.voiceshopping.common.constant.RedisKeys;
import com.voiceshopping.common.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Triggers cross-session long-term memory writeback when a {@code vs:session:{id}}
 * Redis key expires (i.e. the user walked away silently — no WS close, no order).
 * <p>
 * Wiring: subclass of {@link KeyExpirationEventMessageListener} so Spring auto-subscribes
 * to the {@code __keyevent@*__:expired} channel for us. The {@link RedisMessageListenerContainer}
 * + this bean are conditionally created only when
 * {@code voice-shopping.memory.session-expire-listener.enabled=true} (see
 * {@link com.voiceshopping.infrastructure.config.SessionExpireListenerConfig}).
 * <p>
 * Redis MUST be configured with {@code notify-keyspace-events=Ex} for the
 * subscription to receive any message — pair this listener with the
 * {@code voice-shopping.memory.keyspace-notification.check-on-startup} switch
 * to fail fast when the upstream Redis is misconfigured.
 */
public class SessionExpireListener extends KeyExpirationEventMessageListener {

    private static final Logger log = LoggerFactory.getLogger(SessionExpireListener.class);

    /** {@link RedisKeys#sessionState(String)} prefix. */
    private static final String SESSION_KEY_PREFIX = "vs:session:";

    private final SessionService sessionService;
    private final LongTermMemoryWriter longTermMemoryWriter;

    public SessionExpireListener(RedisMessageListenerContainer listenerContainer,
                                 SessionService sessionService,
                                 LongTermMemoryWriter longTermMemoryWriter) {
        super(listenerContainer);
        this.sessionService = sessionService;
        this.longTermMemoryWriter = longTermMemoryWriter;
    }

    /**
     * Receives {@code __keyevent@*__:expired} messages. The body of every such
     * message IS the expired key (UTF-8 bytes), regardless of database number.
     * <p>
     * Filtering: only keys with the {@code vs:session:} prefix matter — anything
     * else (short_memory, intent_cache, foreign apps sharing the Redis instance)
     * is silently ignored.
     */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody());
        if (!expiredKey.startsWith(SESSION_KEY_PREFIX)) {
            return;
        }
        String sessionId = expiredKey.substring(SESSION_KEY_PREFIX.length());
        if (sessionId.isBlank()) {
            log.warn("Ignoring expired key with empty sessionId: {}", expiredKey);
            return;
        }

        Long userId;
        try {
            userId = sessionService.findUserId(sessionId);
        } catch (NotFoundException e) {
            // Race: PG row already deleted, or sessionId crafted by another app sharing
            // the Redis namespace — neither is a bug. Skip silently at INFO.
            log.info("Session expire ignored — no PG row: sessionId={}", sessionId);
            return;
        } catch (Exception e) {
            // Any other PG hiccup: log loudly but never throw — pub/sub callbacks
            // that throw block subsequent listeners.
            log.error("Session expire userId lookup failed: sessionId={}", sessionId, e);
            return;
        }

        try {
            longTermMemoryWriter.flushOnSessionEnd(sessionId, userId);
            log.info("Session expire flush dispatched: sessionId={}, userId={}", sessionId, userId);
        } catch (Exception e) {
            // flushOnSessionEnd is @Async — only fail-fast guard exceptions reach here.
            log.error("Session expire flush dispatch failed: sessionId={}, userId={}", sessionId, userId, e);
        }
    }
}
