package com.voiceshopping.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Debug request for the recommendation pipeline.
 */
public record RecommendDebugReq(
        @NotBlank String utterance,
        Map<String, Object> slots
) {
}
