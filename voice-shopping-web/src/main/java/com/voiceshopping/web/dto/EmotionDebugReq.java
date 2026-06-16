package com.voiceshopping.web.dto;

import com.voiceshopping.common.dto.agent.RecommendResult;

/**
 * Debug request body for the emotion agent endpoint.
 *
 * @param utterance user's raw utterance
 * @param rec       recommendation result to wrap (caller-supplied for isolated testing)
 * @param userNeeds slot-derived needs summary (optional, defaults to empty string)
 */
public record EmotionDebugReq(
        String utterance,
        RecommendResult rec,
        String userNeeds
) {
    public String userNeeds() {
        return userNeeds != null ? userNeeds : "";
    }
}
