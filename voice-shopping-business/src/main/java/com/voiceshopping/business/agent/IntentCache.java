package com.voiceshopping.business.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.constant.RedisKeys;
import com.voiceshopping.common.dto.agent.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * Fingerprint-level intent result cache backed by Redis.
 * <p>
 * Session-scoped: the same utterance + history within a session
 * produces the same fingerprint, avoiding repeated LLM calls on
 * network retry or user rephrasing. Natural lifecycle — expires
 * with the session, no cross-session pollution.
 */
@Component
public class IntentCache {

    private static final Logger log = LoggerFactory.getLogger(IntentCache.class);
    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public IntentCache(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * Look up a cached intent result.
     *
     * @param sessionId current session ID
     * @param hash      SHA-256 hash of (utterance + historyText)
     * @return cached IntentResult, or null if miss
     */
    public IntentResult get(String sessionId, String hash) {
        String key = RedisKeys.intentCache(sessionId, hash);
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, IntentResult.class);
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached intent, deleting key: {}", key);
            redis.delete(key);
            return null;
        }
    }

    /**
     * Store an intent result in cache.
     *
     * @param sessionId current session ID
     * @param hash      SHA-256 hash of (utterance + historyText)
     * @param result    the intent result to cache
     */
    public void put(String sessionId, String hash, IntentResult result) {
        try {
            String key = RedisKeys.intentCache(sessionId, hash);
            String json = objectMapper.writeValueAsString(result);
            redis.opsForValue().set(key, json, TTL);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize intent result for caching", e);
        }
    }

    /**
     * Compute SHA-256 hash from the given fields.
     */
    public static String computeHash(String utterance, String historyText) {
        String input = utterance + "|" + historyText;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
