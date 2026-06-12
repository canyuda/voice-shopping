package com.voiceshopping.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Downstream JSON signal — error notification sent to the client.
 */
public record VoiceError(
        @JsonProperty("type") String type,
        @JsonProperty("code") String code,
        @JsonProperty("message") String message
) {
    public VoiceError(String code, String message) {
        this("error", code, message);
    }
}
