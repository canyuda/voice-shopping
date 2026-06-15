package com.voiceshopping.business.scope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.dto.session.SessionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionScopeCacheTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SessionScopeCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        cache = new SessionScopeCache(redis, new ObjectMapper(), Duration.ofMinutes(30));
    }

    @Test
    void put_writesJsonWithConfiguredTtl() {
        SessionScope scope = new SessionScope(7L, List.of(5L), 88L);

        cache.put("sess-1", scope);

        // Key prefixed with vs:scope:, TTL passed through to opsForValue.
        verify(valueOps).set(eq("vs:scope:sess-1"), any(String.class), eq(Duration.ofMinutes(30)));
    }

    @Test
    void get_returnsParsedScopeOnHit() {
        SessionScope scope = new SessionScope(7L, List.of(5L), 88L);
        ObjectMapper mapper = new ObjectMapper();
        try {
            when(valueOps.get("vs:scope:sess-1")).thenReturn(mapper.writeValueAsString(scope));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        Optional<SessionScope> result = cache.get("sess-1");

        assertThat(result).contains(scope);
    }

    @Test
    void get_returnsEmptyOnCacheMiss() {
        when(valueOps.get("vs:scope:never-set")).thenReturn(null);

        assertThat(cache.get("never-set")).isEmpty();
    }

    @Test
    void get_returnsEmptyOnRedisFailure() {
        when(valueOps.get("vs:scope:sess-1"))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(cache.get("sess-1")).isEmpty();
    }

    @Test
    void put_swallowsRedisFailure() {
        SessionScope scope = SessionScope.platformWide(7L);
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOps).set(any(String.class), any(String.class), any(Duration.class));

        // Should not throw — cache miss is a defined fallback path.
        cache.put("sess-1", scope);
    }
}
