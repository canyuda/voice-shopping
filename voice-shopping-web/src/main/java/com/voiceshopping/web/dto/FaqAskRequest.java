package com.voiceshopping.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /api/v1/faq/ask
 *
 * @param merchantId optional — defaults to 0 (platform-wide FAQ only) when not provided
 */
public record FaqAskRequest(
        Long merchantId,
        @NotBlank String question
) {}
