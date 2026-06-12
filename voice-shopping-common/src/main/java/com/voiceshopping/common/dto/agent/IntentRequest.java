package com.voiceshopping.common.dto.agent;

/**
 * Debug request for the intent classification endpoint.
 *
 * @param sessionId current session ID (also scopes the intent cache)
 * @param utterance user's latest utterance
 */
public record IntentRequest(String sessionId, String utterance) {
}
