package com.voiceshopping.web.dto;

/**
 * Response body for {@code POST /api/v1/auth/login}: an opaque Sa-Token value
 * the client passes back via {@code Sec-WebSocket-Protocol: Bearer-{token}}.
 */
public record LoginResponse(String token) {
}
