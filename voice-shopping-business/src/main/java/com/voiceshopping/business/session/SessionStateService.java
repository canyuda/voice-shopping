package com.voiceshopping.business.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.constant.RedisKeys;
import com.voiceshopping.infrastructure.repository.SessionStateRepository;
import com.voiceshopping.infrastructure.repository.entity.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

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

    public SessionStateService(SessionStateRepository repository,
                               StringRedisTemplate redis,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Load session state. Redis-first with PG fallback.
     */
    public Optional<SessionState> load(UUID sessionId) {
        String key = RedisKeys.sessionState(sessionId.toString());

        // Try Redis first
        try {
            String raw = redis.opsForValue().get(key);
            if (raw != null) {
                SessionState state = objectMapper.readValue(raw, SessionState.class);
                log.trace("Session state loaded from Redis: sessionId={}", sessionId);
                return Optional.of(state);
            }
        } catch (Exception e) {
            log.warn("Failed to read session state from Redis, falling back to PG: sessionId={}", sessionId, e);
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
     * Redis write failure is logged but does not propagate.
     */
    public SessionState save(SessionState state) {
        // PG write (source of truth)
        SessionState saved = repository.save(state);

        // Redis write (best effort)
        String key = RedisKeys.sessionState(saved.getId().toString());
        writeToRedis(key, saved);

        return saved;
    }

    private void writeToRedis(String key, SessionState state) {
        try {
            String json = objectMapper.writeValueAsString(state);
            redis.opsForValue().set(key, json);
        } catch (Exception e) {
            log.error("Failed to write session state to Redis: key={}, error={}", key, e.getMessage());
            // Intentionally not re-throwing — PG is the source of truth
        }
    }
}
