package com.voiceshopping.common.dto.order;

import java.math.BigDecimal;

/**
 * Preview-state order, kept in Redis until the user confirms or aborts.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>{@code OrderService.preview} writes one record per session
 *       under {@code vs:pending_order:{sessionId}} with TTL.</li>
 *   <li>{@code OrderService.confirm} reads it, decrements stock, persists
 *       {@code OrderRecord}, and removes the Redis key.</li>
 *   <li>{@code OrderService.cancel} (or TTL expiry) removes the key without
 *       writing anything to PG.</li>
 * </ol>
 *
 * @param sessionId    owning session
 * @param userId       buyer
 * @param merchantId   bound merchant (must match {@code product.merchantId} at confirm time)
 * @param productId    product being ordered
 * @param productName  cached product name (avoids one DB hit on the confirm prompt)
 * @param skuCode      product SKU (nullable when product has no SKU code)
 * @param quantity     units (currently fixed at 1; multi-unit is V2)
 * @param unitPrice    snapshot of {@code product.price} at preview time
 * @param totalAmount  {@code unitPrice * quantity}, pre-computed
 */
public record PendingOrder(
        String sessionId,
        Long userId,
        Long merchantId,
        Long productId,
        String productName,
        String skuCode,
        int quantity,
        BigDecimal unitPrice,
        BigDecimal totalAmount
) {
}
