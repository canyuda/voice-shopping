package com.voiceshopping.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Request body for the perspective hub debug endpoint.
 * Drives both the recommendation pipeline and the perspective discussion.
 */
public record PerspectiveHubReq(
        @NotBlank String sessionId,
        @NotNull Long userId,
        @NotBlank String utterance,
        Map<String, Object> slots
) {
}
