package com.voiceshopping.common.dto;

import java.util.List;

/**
 * Lean page wrapper — replaces Spring's {@code Page} in HTTP responses to
 * avoid serializing the noisy {@code pageable} / {@code sort} / {@code empty}
 * / {@code numberOfElements} fields that clients never use.
 * <p>
 * Page numbers stay <strong>0-based</strong>, matching the
 * {@code ?page=} query parameter Spring binds via
 * {@code PageableHandlerMethodArgumentResolver} — what the client sends is
 * what it gets back, no off-by-one translation.
 * <p>
 * Lives in {@code voice-shopping-common} and intentionally does NOT depend
 * on Spring Data — Service-layer methods return {@code PageInfo} directly
 * instead of leaking the {@code Page} abstraction up. Adapt from a
 * {@code Page} at the Service boundary via the helper in the
 * infrastructure or business module that owns the conversion.
 *
 * @param list       items on this page
 * @param page       current page number (0-based)
 * @param size       page size
 * @param total      total element count across all pages
 * @param totalPages total page count (included as a convenience so each
 *                   caller does not have to recompute {@code ceil(total/size)})
 */
public record PageInfo<T>(
        List<T> list,
        int page,
        int size,
        long total,
        int totalPages
) {
}
