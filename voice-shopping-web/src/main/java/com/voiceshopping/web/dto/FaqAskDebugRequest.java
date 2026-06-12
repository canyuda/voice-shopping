package com.voiceshopping.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/v1/faq/ask-debug
 *
 * @param merchantId optional — defaults to 0 (platform-wide FAQ only) when not provided
 */
public record FaqAskDebugRequest(
        Long merchantId,
        @NotBlank String question,
        Integer topN
) {
    public int resolveTopN() {
        return topN != null ? topN : 5;
    }

    public long resolveMerchantId() {
        return merchantId != null ? merchantId : 0L;
    }
}
