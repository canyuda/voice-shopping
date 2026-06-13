package com.voiceshopping.common.dto.agent;

import java.util.Map;

/**
 * Debug request for the clarify endpoint.
 *
 * @param sessionId current session ID
 * @param utterance user's latest utterance
 * @param slots     currently extracted slot values from intent agent
 */
public record ClarifyDebugReq(
        String sessionId,
        String utterance,
        Map<String, Object> slots
) {
}
