package com.voiceshopping.web.dto;

/**
 * Request body for {@code POST /api/v1/debug/memory/flush}.
 *
 * @param sessionId session whose slots should be flushed (required, non-blank)
 * @param userId    profile owner; when null the controller resolves it from the session row
 */
public record MemoryFlushRequest(String sessionId, Long userId) {
}
