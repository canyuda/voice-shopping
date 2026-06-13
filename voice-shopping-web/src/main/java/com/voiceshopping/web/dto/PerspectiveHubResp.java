package com.voiceshopping.web.dto;

import com.voiceshopping.common.dto.agent.RecommendResult;

/**
 * Response body for the perspective hub debug endpoint.
 *
 * @param perspectiveText three-line concatenated text (price advisor / pro user / beginner buyer);
 *                        empty string if items were empty or discussion failed.
 * @param recommendation  the underlying recommendation result.
 */
public record PerspectiveHubResp(
        String perspectiveText,
        RecommendResult recommendation
) {
}
