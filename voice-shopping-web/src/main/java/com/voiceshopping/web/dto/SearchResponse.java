package com.voiceshopping.web.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response body for GET /api/v1/search
 */
public record SearchResponse(
        String query,
        int count,
        List<SearchItem> results
) {
    public record SearchItem(
            long productId,
            String name,
            String categoryL1,
            String categoryL2,
            BigDecimal price,
            double similarity
    ) {}
}
