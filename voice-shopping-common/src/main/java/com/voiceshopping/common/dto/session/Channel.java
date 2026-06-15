package com.voiceshopping.common.dto.session;

/**
 * Session entry channels.
 * <p>
 * Determines how merchant scope is resolved at {@code /api/v1/session/start}:
 * <ul>
 *   <li>{@link #HOME_ENTRY} — platform homepage, scope = platform-wide.</li>
 *   <li>{@link #PRODUCT_PAGE} — product detail page, scope locked to the
 *       merchant of {@code boundProductId}.</li>
 *   <li>{@link #MERCHANT_HOME} — merchant store homepage, scope locked to
 *       the explicit {@code merchantId}.</li>
 *   <li>{@link #SEARCH_FALLBACK} — search-no-result fallback, scope = platform-wide.</li>
 * </ul>
 */
public enum Channel {
    HOME_ENTRY,
    PRODUCT_PAGE,
    MERCHANT_HOME,
    SEARCH_FALLBACK
}
