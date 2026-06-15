package com.voiceshopping.common.dto.order;

import java.math.BigDecimal;

/**
 * One line item within {@link OrderDTO#items()}. Schema mirrors the
 * V1 migration's {@code COMMENT ON COLUMN order_record.items} contract.
 */
public record OrderItemDTO(
        Long productId,
        String name,
        BigDecimal price,
        Integer quantity
) {
}
