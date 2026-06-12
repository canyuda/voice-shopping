package com.voiceshopping.web.dto;

import java.util.List;

/**
 * Response body for POST /api/v1/faq/ask-debug
 */
public record FaqAskDebugResponse(
        List<FaqCandidate> results
) {
    public record FaqCandidate(
            long id,
            String question,
            String answer,
            String category,
            double similarity
    ) {}
}
