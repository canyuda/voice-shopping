package com.voiceshopping.common.dto.agent;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A single recommended product item with match score and optional reason.
 * Immutable — use {@code with...} methods to create modified copies.
 *
 * @param productId  product ID
 * @param name       product name
 * @param price      product price
 * @param reason     LLM-generated recommendation reason (may be empty)
 * @param matchScore weighted match score (cosine similarity + profile bonuses)
 * @param attributes product attributes (JSONB)
 */
public record RecommendedItem(
        Long productId,
        String name,
        BigDecimal price,
        String reason,
        double matchScore,
        Map<String, Object> attributes
) {
    /** Return a new instance with updated match score. */
    public RecommendedItem withMatchScore(double s) {
        return new RecommendedItem(productId, name, price, reason, s, attributes);
    }

    /** Return a new instance with updated reason. */
    public RecommendedItem withReason(String r) {
        return new RecommendedItem(productId, name, price, r, matchScore, attributes);
    }
}
