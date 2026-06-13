package com.voiceshopping.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Debug request body for the user-spoken event endpoint.
 */
public record UserSpokenEventReq(
        @NotBlank String sessionId,
        Long userId,
        @NotBlank String utterance
) {
}
