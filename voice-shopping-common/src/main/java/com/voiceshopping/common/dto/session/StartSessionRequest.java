package com.voiceshopping.common.dto.session;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/session/start}.
 *
 * @param sessionId       caller-supplied session id (≤ 64 chars)
 * @param channel         entry channel — drives scope resolution
 * @param merchantId      required when channel = {@link Channel#MERCHANT_HOME}
 * @param boundProductId  required when channel = {@link Channel#PRODUCT_PAGE}
 */
public record StartSessionRequest(
        @NotBlank String sessionId,
        @NotNull Channel channel,
        Long merchantId,
        Long boundProductId
) {
}
