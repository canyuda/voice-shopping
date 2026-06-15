package com.voiceshopping.business.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.constant.RedisKeys;
import com.voiceshopping.common.dto.order.PendingOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Side-cache for {@link PendingOrder}, keyed by {@code sessionId}.
 * <p>
 * Lifecycle: written by {@code OrderService.preview}, read by
 * {@code OrchestratorService.handleOrderConfirm} and
 * {@code OrderService.confirm}, removed on confirm/cancel/TTL expiry.
 * <p>
 * Cache miss is a normal condition that callers MUST handle — the
 * orchestrator falls back to "no pending → resolve reference" path when
 * {@link #get(String)} returns {@code null}.
 */
@Component
public class PendingOrderStore {

    private static final Logger log = LoggerFactory.getLogger(PendingOrderStore.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public PendingOrderStore(StringRedisTemplate redis,
                             ObjectMapper objectMapper,
                             @Value("${voice-shopping.order.pending-ttl-seconds:600}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        log.info("PendingOrderStore initialized: ttlSeconds={}", ttlSeconds);
    }

    /**
     * Write the pending order JSON with a fresh TTL. Same {@code sessionId}
     * → key is overwritten (used when the user changes mind mid-preview
     * and immediately picks a different product).
     * <p>
     * Serialization failure is a programming error — propagated as
     * {@link IllegalStateException}. Redis I/O failure is similarly
     * propagated to keep "fail-fast on order path" semantics; OrderService
     * MUST surface the error to the user rather than silently skip.
     */
    public void put(PendingOrder order) {
        if (order == null || order.sessionId() == null || order.sessionId().isBlank()) {
            throw new IllegalArgumentException("PendingOrder.sessionId must not be blank");
        }
        String key = RedisKeys.pendingOrder(order.sessionId());
        try {
            String json = objectMapper.writeValueAsString(order);
            redis.opsForValue().set(key, json, ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize PendingOrder for sessionId=" + order.sessionId(), e);
        }
    }

    /**
     * Returns {@code null} on cache miss, deserialization failure, or
     * Redis I/O failure (logged at WARN). Callers MUST treat {@code null}
     * as "no pending order — fall back to reference resolution".
     */
    public PendingOrder get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String key = RedisKeys.pendingOrder(sessionId);
        try {
            String raw = redis.opsForValue().get(key);
            if (raw == null) {
                return null;
            }
            return objectMapper.readValue(raw, PendingOrder.class);
        } catch (Exception e) {
            log.warn("PendingOrderStore get failed sessionId={}, treating as miss", sessionId, e);
            return null;
        }
    }

    /**
     * Idempotent removal. Missing key is not an error.
     */
    public void remove(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String key = RedisKeys.pendingOrder(sessionId);
        try {
            redis.delete(key);
        } catch (Exception e) {
            log.warn("PendingOrderStore remove failed sessionId={}", sessionId, e);
        }
    }
}
