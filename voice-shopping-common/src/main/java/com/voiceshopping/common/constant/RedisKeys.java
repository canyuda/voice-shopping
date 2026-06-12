package com.voiceshopping.common.constant;

/**
 * Centralized Redis key definitions for the voice-shopping system.
 * All keys use the {@code vs:} prefix to avoid collisions with other systems.
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    private static final String PREFIX = "vs:";

    // ------------------------------------------------------------------
    // Session state: vs:session:{sessionId}
    // Type: Hash | TTL: 30min | Source: session_state (PG)
    // ------------------------------------------------------------------

    private static final String SESSION_STATE = PREFIX + "session:";

    public static String sessionState(long sessionId) {
        return SESSION_STATE + sessionId;
    }

    /**
     * Overload for UUID-based session IDs.
     */
    public static String sessionState(String sessionId) {
        return SESSION_STATE + sessionId;
    }

    // ------------------------------------------------------------------
    // Short-term memory: vs:short_memory:{sessionId}
    // Type: List | TTL: 30min | Source: session_message (PG)
    // ------------------------------------------------------------------

    private static final String SHORT_MEMORY = PREFIX + "short_memory:";

    public static String shortMemory(long sessionId) {
        return SHORT_MEMORY + sessionId;
    }

    /**
     * Overload for UUID-based session IDs.
     */
    public static String shortMemory(String sessionId) {
        return SHORT_MEMORY + sessionId;
    }

    // ------------------------------------------------------------------
    // User profile cache: vs:user:profile:{userId}
    // Type: Hash | TTL: 24h | Source: user_profile_static + user_profile_dynamic (PG)
    // ------------------------------------------------------------------

    private static final String USER_PROFILE = PREFIX + "user:profile:";

    public static String userProfile(long userId) {
        return USER_PROFILE + userId;
    }

    // ------------------------------------------------------------------
    // Intent cache: vs:intent_cache:{sessionId}:{hash}
    // Type: String (JSON) | TTL: 5min
    // hash = SHA256(utterance + historyText)
    // Session-scoped: aligns with ShortTermMemory / AgentFactory lifecycle.
    // ------------------------------------------------------------------

    private static final String INTENT_CACHE = PREFIX + "intent_cache:";

    public static String intentCache(String sessionId, String hash) {
        return INTENT_CACHE + sessionId + ":" + hash;
    }

    // ------------------------------------------------------------------
    // Recommendation cache: vs:rec_cache:{hash}
    // Type: String (JSON) | TTL: 10min
    // hash = SHA256(merchantId + slots JSON + userProfileHash)
    // ------------------------------------------------------------------

    private static final String REC_CACHE = PREFIX + "rec_cache:";

    public static String recCache(String hash) {
        return REC_CACHE + hash;
    }
}
