package com.voiceshopping.infrastructure.vector;

import com.voiceshopping.common.dto.agent.Filter;
import com.voiceshopping.common.dto.session.SessionScope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds a {@link Filter} fragment of the form {@code merchant_id IN (?,?,...)}
 * from a {@link SessionScope}.
 * <p>
 * Output is designed to compose with {@link SqlFilterBuilder#merge(Filter, Filter)}
 * — null / platform-wide scopes return {@link Filter#EMPTY}, so merging always
 * produces the right SQL whether scope is in effect or not.
 */
@Component
public class ScopeFilterBuilder {

    /**
     * Translate a session scope into a SQL filter fragment.
     * Returns {@link Filter#EMPTY} when scope is null or platform-wide.
     */
    public Filter build(SessionScope scope) {
        if (scope == null || scope.isPlatformWide()) {
            return Filter.EMPTY;
        }

        List<Long> merchantIds = scope.allowedMerchantIds();
        String placeholders = String.join(", ", Collections.nCopies(merchantIds.size(), "?"));
        String clause = "merchant_id IN (" + placeholders + ")";

        // Copy params to break aliasing with the scope record's list and to
        // type the elements as Object for downstream JdbcTemplate binding.
        List<Object> params = new ArrayList<>(merchantIds.size());
        params.addAll(merchantIds);

        return new Filter(clause, params);
    }
}
