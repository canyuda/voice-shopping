package com.voiceshopping.business.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.dto.order.PendingOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-Mockito unit tests for {@link PendingOrderStore}. Mirrors the spec
 * scenarios in {@code order-placement-flow/spec.md}.
 */
class PendingOrderStoreTest {

    private static final String SESSION_ID = "sess-1";
    private static final long TTL_SECONDS = 120;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ObjectMapper objectMapper;
    private PendingOrderStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        objectMapper = new ObjectMapper();
        store = new PendingOrderStore(redis, objectMapper, TTL_SECONDS);
    }

    private PendingOrder samplePending() {
        return new PendingOrder(
                SESSION_ID, 100L, 1L, 8821L,
                "Asics GEL-Contend 9", "AS-001", 1,
                new BigDecimal("479.00"), new BigDecimal("479.00"));
    }

    @Test
    void put_writesJsonWithConfiguredTtl() {
        store.put(samplePending());

        verify(valueOps).set(
                eq("vs:pending_order:sess-1"),
                any(String.class),
                eq(Duration.ofSeconds(TTL_SECONDS)));
    }

    @Test
    void get_returnsParsedOrderOnHit() throws Exception {
        PendingOrder po = samplePending();
        when(valueOps.get("vs:pending_order:sess-1"))
                .thenReturn(objectMapper.writeValueAsString(po));

        PendingOrder result = store.get(SESSION_ID);

        assertThat(result).isEqualTo(po);
    }

    @Test
    void get_returnsNullOnCacheMiss() {
        when(valueOps.get("vs:pending_order:never-set")).thenReturn(null);

        assertThat(store.get("never-set")).isNull();
    }

    @Test
    void get_returnsNullOnDeserializationFailure() {
        when(valueOps.get("vs:pending_order:sess-1")).thenReturn("{ this is not valid json");

        // Treat malformed payload as a miss instead of crashing the order path.
        assertThat(store.get(SESSION_ID)).isNull();
    }

    @Test
    void get_returnsNullOnRedisFailure() {
        when(valueOps.get("vs:pending_order:sess-1"))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(store.get(SESSION_ID)).isNull();
    }

    @Test
    void get_returnsNullForBlankSessionId() {
        // Defensive — never hit Redis with an empty key.
        assertThat(store.get(null)).isNull();
        assertThat(store.get("")).isNull();
        assertThat(store.get("   ")).isNull();
    }

    @Test
    void remove_isIdempotent() {
        // Normal call.
        store.remove(SESSION_ID);
        verify(redis).delete("vs:pending_order:sess-1");

        // Blank-id call should NOT touch Redis at all.
        store.remove("");
        store.remove(null);
        // Only the one valid invocation counts.
        verify(redis, org.mockito.Mockito.times(1)).delete(any(String.class));
    }

    @Test
    void remove_swallowsRedisFailure() {
        when(redis.delete("vs:pending_order:sess-1"))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        // Should not throw — failed remove is a benign double-delete shape.
        store.remove(SESSION_ID);
    }

    @Test
    void put_failsFastOnBlankSessionId() {
        PendingOrder bad = new PendingOrder(
                "  ", 100L, 1L, 8821L,
                "Asics", "AS-001", 1,
                new BigDecimal("479.00"), new BigDecimal("479.00"));
        assertThatThrownBy(() -> store.put(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionId");
    }
}
