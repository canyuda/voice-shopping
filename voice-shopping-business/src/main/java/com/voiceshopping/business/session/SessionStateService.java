package com.voiceshopping.business.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.constant.RedisKeys;
import com.voiceshopping.infrastructure.repository.SessionStateRepository;
import com.voiceshopping.infrastructure.repository.entity.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;

/**
 * SessionState read/write with dual-write: PG as source of truth, Redis as hot cache.
 * <p>
 * Load: Redis first, fallback to PG on miss.
 * Save: PG first, then Redis (failure tolerated).
 */
@Service
public class SessionStateService {

    private static final Logger log = LoggerFactory.getLogger(SessionStateService.class);

    private final SessionStateRepository repository;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;

    public SessionStateService(SessionStateRepository repository,
                               StringRedisTemplate redis,
                               ObjectMapper objectMapper,
                               @Value("${voice-shopping.memory.session-state.ttl:30m}") Duration ttl) {
        this.repository = repository;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        log.info("SessionStateService initialized: ttl={}", ttl);
    }

    /**
     * Load session state. Redis-first with PG fallback.
     */
    public Optional<SessionState> load(String sessionId) {
        String key = RedisKeys.sessionState(sessionId);

        // Try Redis first
        try {
            String raw = redis.opsForValue().get(key);
            if (raw != null) {
                SessionState state = objectMapper.readValue(raw, SessionState.class);
                log.trace("Session state loaded from Redis: sessionId={}", sessionId);
                return Optional.of(state);
            }
        } catch (Exception e) {
            log.warn("Redis 读取失败，回退 PG，下次 load 会从 PG 重建 sessionId={}", sessionId, e);
        }

        // Fallback to PG
        Optional<SessionState> fromPg = repository.findById(sessionId);
        fromPg.ifPresent(state -> {
            writeToRedis(key, state);
            log.trace("Session state loaded from PG and cached to Redis: sessionId={}", sessionId);
        });

        return fromPg;
    }

    /**
     * Save session state: PG first, then Redis.
     * Redis write failure is logged but does not propagate. {@code noRollbackFor}
     * keeps the PG transaction committed even when Redis I/O fails — Redis is just
     * a hot cache, and the next {@link #load(String)} will rebuild it from PG.
     */
    @Transactional(noRollbackFor = org.springframework.data.redis.RedisConnectionFailureException.class)
    public SessionState save(SessionState state) {
        // PG write (source of truth)
        SessionState saved = repository.save(state);

        // Redis write (best effort)
        String key = RedisKeys.sessionState(saved.getId());
        writeToRedis(key, saved);

        return saved;
    }

    private void writeToRedis(String key, SessionState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            // TTL is mandatory: it's the trigger for SessionExpireListener (long-term
            // memory writeback on silent abandonment). Plain set() without TTL would
            // make sessions live forever in Redis and the listener would never fire.
            redis.opsForValue().set(key, json, ttl);
        } catch (org.springframework.data.redis.RedisConnectionFailureException e) {
            log.warn("Redis 同步失败，下次 load 会从 PG 重建 sessionId={}", state.getId(), e);
            // Intentionally not re-throwing — PG is the source of truth.
        } catch (JsonProcessingException e) {
            log.error("解析json失败");
        }
    }
}
