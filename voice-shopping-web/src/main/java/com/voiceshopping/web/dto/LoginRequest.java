package com.voiceshopping.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /api/v1/auth/login}.
 * Phone is treated as the global login identifier in this version
 * (no password check yet — see merchant-data-isolation Decision 1).
 */
public record LoginRequest(@NotBlank String phone) {
}
