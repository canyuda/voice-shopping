package com.voiceshopping.web.dto;

/**
 * Response body for POST /api/v1/faq/ask
 */
public record FaqAskResponse(
        boolean found,
        long id,
        String question,
        String answer,
        String category,
        double similarity
) {
    public static FaqAskResponse notFound() {
        return new FaqAskResponse(false, 0, "", "", "", 0.0);
    }
}
