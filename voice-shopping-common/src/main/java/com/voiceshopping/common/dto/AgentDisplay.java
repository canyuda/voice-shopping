package com.voiceshopping.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.voiceshopping.common.dto.agent.RecommendedItem;

import java.util.List;

/**
 * Downstream JSON signal — UI display blocks produced by the orchestrator,
 * delivered alongside (and ahead of) the TTS audio frames.
 */
public record AgentDisplay(
        @JsonProperty("type") String type,
        @JsonProperty("blocks") List<RecommendedItem> blocks
) {
    public AgentDisplay(List<RecommendedItem> blocks) {
        this("agent_display", blocks);
    }
}
