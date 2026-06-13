package com.voiceshopping.common.dto.agent;

import java.util.List;

/**
 * Final result of the product recommendation pipeline.
 *
 * @param items            top-K recommended items with reasons
 * @param explanationTone  tone label, e.g. "professional" or "empty" when no results
 */
public record RecommendResult(
        List<RecommendedItem> items,
        String explanationTone
) {
    /** Empty result with no recommendations. */
    public static final RecommendResult EMPTY = new RecommendResult(List.of(), "empty");
}
