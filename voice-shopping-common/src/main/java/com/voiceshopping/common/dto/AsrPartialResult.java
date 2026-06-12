package com.voiceshopping.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Downstream JSON signal — ASR partial (incremental) recognition result.
 */
public record AsrPartialResult(
        @JsonProperty("type") String type,
        @JsonProperty("text") String text
) {
    public AsrPartialResult(String text) {
        this("asr_partial", text);
    }
}
