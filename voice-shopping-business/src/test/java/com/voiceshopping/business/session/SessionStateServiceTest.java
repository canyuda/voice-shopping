package com.voiceshopping.business.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.infrastructure.repository.SessionStateRepository;
import com.voiceshopping.infrastructure.repository.entity.SessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies dual-write fault tolerance: PG remains the source of truth, Redis
 * write failures are logged but never propagated to the caller.
 */
class SessionStateServiceTest {

    private static final String SESSION_ID = "sess-1";

    private SessionStateRepository repository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SessionStateService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        repository = mock(SessionStateRepository.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        service = new SessionStateService(repository, redis, new ObjectMapper(), Duration.ofMinutes(30));
    }

    @Test
    void save_redisWriteFailure_doesNotPropagate_pgStateReturned() {
        SessionState state = new SessionState();
        state.setId(SESSION_ID);
        state.setMerchantId(1L);
        when(repository.save(state)).thenReturn(state);
        // Production code only swallows the documented Redis I/O failure type;
        // a generic RuntimeException would (correctly) propagate. Match production.
        doThrow(new RedisConnectionFailureException("redis down"))
                .when(valueOps).set(anyString(), anyString(), any(Duration.class));

        SessionState saved = service.save(state);

        assertThat(saved).isSameAs(state);
        verify(repository).save(state);
        verify(valueOps).set(eq("vs:session:" + SESSION_ID), anyString(), any(Duration.class));
    }

    @Test
    void save_happyPath_writesPgThenRedisWithTtl() {
        SessionState state = new SessionState();
        state.setId(SESSION_ID);
        when(repository.save(state)).thenReturn(state);

        service.save(state);

        verify(repository).save(state);
        verify(valueOps).set(eq("vs:session:" + SESSION_ID), anyString(), eq(Duration.ofMinutes(30)));
    }
}
