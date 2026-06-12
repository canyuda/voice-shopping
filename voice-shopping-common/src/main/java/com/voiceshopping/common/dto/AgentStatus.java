package com.voiceshopping.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Downstream JSON signal — agent processing status notification.
 */
public record AgentStatus(
        @JsonProperty("type") String type,
        @JsonProperty("status") String status
) {
    public AgentStatus(String status) {
        this("agent_status", status);
    }
}
