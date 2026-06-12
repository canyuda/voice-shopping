package com.voiceshopping.business.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.constant.RedisKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Redis List-based short-term conversation memory per session.
 * <p>
 * Stores the most recent dialogue turns for context retrieval by Agents:
 * IntentAgent reads recent 3, SentimentAgent reads recent 2.
 * Orchestrator appends each turn result.
 */
@Component
public class ShortTermMemory {

    private static final Logger log = LoggerFactory.getLogger(ShortTermMemory.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final int maxHistoryTurns;

    public ShortTermMemory(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            @Value("${voice-shopping.memory.short-term.ttl:30m}") Duration ttl,
            @Value("${voice-shopping.memory.short-term.max-history-turns:20}") int maxHistoryTurns) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.maxHistoryTurns = maxHistoryTurns;
        log.info("ShortTermMemory initialized: ttl={}, maxHistoryTurns={}", ttl, maxHistoryTurns);
    }

    /**
     * A single dialogue turn stored in short-term memory.
     *
     * @param role      USER / ASSISTANT / SYSTEM
     * @param content   the text content of this turn
     * @param turn      sequential turn number within the session
     * @param agent     which agent produced this turn (e.g. "IntentAgent"), null for USER turns
     * @param timestamp when this turn was created
     */
    public record Turn(
            String role,
            String content,
            int turn,
            String agent,
            Instant timestamp
    ) {
    }

    /**
     * Append a turn to the session's memory list.
     * Trims to maxHistoryTurns and sets TTL on first write.
     */
    public void append(String sessionId, Turn turn) {
        String key = RedisKeys.shortMemory(sessionId);
        try {
            String json = objectMapper.writeValueAsString(turn);
            redis.opsForList().rightPush(key, json);
            redis.opsForList().trim(key, -maxHistoryTurns, -1);
            // Set TTL only if key has no expiry (first append)
            Long existingTtl = redis.getExpire(key);
            if (existingTtl == null || existingTtl < 0) {
                redis.expire(key, ttl);
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize Turn for session=" + sessionId, e);
        }
    }

    /**
     * Get the most recent n turns, ordered from oldest to newest.
     */
    public List<Turn> recent(String sessionId, int n) {
        String key = RedisKeys.shortMemory(sessionId);
        List<String> raw = redis.opsForList().range(key, -n, -1);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptyList();
        }
        return raw.stream()
                .map(this::deserializeTurn)
                .toList();
    }

    /**
     * Delete all memory for the given session.
     */
    public void clear(String sessionId) {
        String key = RedisKeys.shortMemory(sessionId);
        redis.delete(key);
    }

    private Turn deserializeTurn(String json) {
        try {
            return objectMapper.readValue(json, Turn.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize Turn: {}", json, e);
            throw new IllegalStateException("Corrupted Turn in Redis", e);
        }
    }
}
