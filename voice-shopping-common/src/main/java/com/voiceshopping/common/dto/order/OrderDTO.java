package com.voiceshopping.common.dto.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Public DTO for an {@code order_record} row, returned by
 * {@code GET /api/v1/orders/mine} and {@code GET /api/v1/orders/{orderId}}.
 * <p>
 * All 16 business columns are exposed verbatim — including
 * {@code receiverPhone} / {@code receiverAddr} / {@code receiverName}.
 * No masking is applied: users view their own orders, and plaintext is
 * the natural expectation. If a "support staff views any order" path is
 * later added, that path SHALL apply masking on its own DTO, not by
 * weakening this one.
 * <p>
 * The entity-to-DTO mapping is performed inside {@code OrderService}
 * (this module cannot reverse-depend on {@code voice-shopping-infrastructure}).
 */
public record OrderDTO(
        Long id,
        Long merchantId,
        Long userId,
        String sessionId,
        String orderNo,
        List<OrderItemDTO> items,
        BigDecimal totalAmount,
        String status,
        Boolean agentAttribution,
        String sourceIntent,
        Map<String, Object> aiContext,
        String receiverName,
        String receiverPhone,
        String receiverAddr,
        Instant createdAt,
        Instant updatedAt
) {
}
