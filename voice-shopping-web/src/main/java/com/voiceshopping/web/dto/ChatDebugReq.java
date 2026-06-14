package com.voiceshopping.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Debug request body for {@code POST /api/v1/chat}.
 *
 * @param sessionId opaque business session id (≤64 chars); will be created if missing
 * @param userId    user id
 * @param utterance text the user "spoke" — same shape orchestrator gets from ASR
 */
public record ChatDebugReq(
        @NotBlank String sessionId,
        @NotNull Long userId,
        @NotBlank String utterance
) {
}
