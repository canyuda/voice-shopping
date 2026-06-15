package com.voiceshopping.business.scope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.constant.RedisKeys;
import com.voiceshopping.common.dto.session.SessionScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Side-cache for {@link SessionScope}, keyed by sessionId.
 * <p>
 * Lifecycle is intentionally piggy-backed on the same TTL as session-state
 * ({@code voice-shopping.memory.session-state.ttl}, default 30m) so the scope
 * cache and session expire together. Cache miss is a normal condition that
 * the call site MUST handle by falling back to {@link SessionScope#platformWide(Long)}
 * — see {@code merchant-data-isolation} spec, Decision 3.
 */
@Component
public class SessionScopeCache {

    private static final Logger log = LoggerFactory.getLogger(SessionScopeCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public SessionScopeCache(StringRedisTemplate redis,
                             ObjectMapper objectMapper,
                             @Value("${voice-shopping.memory.session-state.ttl:30m}") Duration ttl) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        log.info("SessionScopeCache initialized: ttl={}", ttl);
    }

    /**
     * Write scope to Redis with TTL. Redis I/O failure is logged at WARN level
     * and swallowed — the consequence is a single cache miss on the next read,
     * which is itself a defined fallback path.
     */
    public void put(String sessionId, SessionScope scope) {
        String key = RedisKeys.scope(sessionId);
        try {
            String json = objectMapper.writeValueAsString(scope);
            redis.opsForValue().set(key, json, ttl);
        } catch (JsonProcessingException e) {
            // Serialization failure is a programming error — fail-fast.
            throw new IllegalStateException("Failed to serialize SessionScope for sessionId=" + sessionId, e);
        } catch (Exception e) {
            log.warn("SessionScopeCache put failed sessionId={}, scope will miss on next read", sessionId, e);
        }
    }

    /**
     * Returns {@link Optional#empty()} on cache miss, deserialization failure,
     * or Redis I/O error. Callers MUST treat empty as "fall back to platform-wide".
     */
    public Optional<SessionScope> get(String sessionId) {
        String key = RedisKeys.scope(sessionId);
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(raw, SessionScope.class));
        } catch (Exception e) {
            log.warn("SessionScopeCache get failed sessionId={}, treating as miss", sessionId, e);
            return Optional.empty();
        }
    }
}
