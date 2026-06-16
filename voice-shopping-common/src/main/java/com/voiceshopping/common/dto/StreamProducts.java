package com.voiceshopping.common.dto;

import java.util.List;

/**
 * Streaming product cards signal for UI rendering.
 */
public record StreamProducts(
        String type,
        List<?> products
) {
    public StreamProducts(List<?> products) {
        this("stream_products", products);
    }
}
