package com.voiceshopping.infrastructure.vector;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Immutable DTO for product vector search results.
 * Contains product info + cosine similarity score.
 */
public record ProductSearchResult(
        long productId,
        String name,
        String categoryL1,
        String categoryL2,
        BigDecimal price,
        List<String> imageUrls,
        Map<String, Object> attributes,
        double similarity
) {}
