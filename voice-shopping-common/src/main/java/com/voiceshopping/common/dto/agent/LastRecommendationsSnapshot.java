package com.voiceshopping.common.dto.agent;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

/**
 * Snapshot of the last recommendation result, persisted to
 * {@code session_state.last_recommendations} as JSON for
 * the next-turn PRODUCT_COMPARE branch to anchor on actual
 * prices rather than the user-provided budget.
 *
 * @param items      top-K items recommended this turn (may be empty)
 * @param minPrice   minimum price across {@code items}, null when empty
 * @param maxPrice   maximum price across {@code items}, null when empty
 * @param productIds product ids in items order
 */
public record LastRecommendationsSnapshot(
        List<RecommendedItem> items,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        List<Long> productIds
) {

    /** Empty snapshot — used when no items are present. */
    public static final LastRecommendationsSnapshot EMPTY =
            new LastRecommendationsSnapshot(List.of(), null, null, List.of());

    /**
     * Build a snapshot from items, computing min/max prices and id list.
     * Caller is responsible for ensuring items have non-null price/productId
     * — this is a fail-fast value object, not a sanitizer.
     */
    public static LastRecommendationsSnapshot from(List<RecommendedItem> items) {
        if (items == null || items.isEmpty()) {
            return EMPTY;
        }

        BigDecimal min = items.stream()
                .map(RecommendedItem::price)
                .min(Comparator.naturalOrder())
                .orElseThrow();
        BigDecimal max = items.stream()
                .map(RecommendedItem::price)
                .max(Comparator.naturalOrder())
                .orElseThrow();

        List<Long> ids = items.stream()
                .map(RecommendedItem::productId)
                .toList();

        return new LastRecommendationsSnapshot(List.copyOf(items), min, max, ids);
    }
}
