package com.voiceshopping.common.dto.session;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * Per-session merchant scope. Carried in Redis (vs:scope:{sessionId}) and
 * consumed by every retrieval path that hits the product table.
 *
 * @param userId              owning user; may be {@code null} only on the
 *                            {@code /api/v1/search} smoke-test path that
 *                            falls back to platform-wide without a session
 * @param allowedMerchantIds  {@code null} or empty list = platform-wide
 *                            (no merchant_id constraint); otherwise the
 *                            retrieval SQL appends {@code merchant_id IN (...)}
 * @param boundProductId      non-null only for {@code PRODUCT_PAGE} channel
 */
public record SessionScope(
        Long userId,
        List<Long> allowedMerchantIds,
        Long boundProductId
) {

    @JsonIgnore
    public boolean isPlatformWide() {
        return allowedMerchantIds == null || allowedMerchantIds.isEmpty();
    }

    /**
     * Fallback scope when the cache misses (Redis expiry / failure).
     * Equivalent to "this user, no merchant constraint" — see
     * {@code merchant-data-isolation} spec, Decision 3.
     */
    public static SessionScope platformWide(Long userId) {
        return new SessionScope(userId, List.of(), null);
    }
}
