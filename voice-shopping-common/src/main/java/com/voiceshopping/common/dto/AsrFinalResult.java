package com.voiceshopping.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Downstream JSON signal — ASR final (sentence-end) recognition result.
 */
public record AsrFinalResult(
        @JsonProperty("type") String type,
        @JsonProperty("text") String text
) {
    public AsrFinalResult(String text) {
        this("asr_final", text);
    }
}
