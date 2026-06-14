package com.voiceshopping.infrastructure.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.common.dto.agent.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for the priceMin / excludeProductIds slot branches added in this change.
 * Other slot branches (budget, category, brand, ...) are not covered here — they predate
 * this change and are exercised by integration paths.
 */
class SqlFilterBuilderTest {

    private SqlFilterBuilder builder;

    @BeforeEach
    void setup() {
        builder = new SqlFilterBuilder(new ObjectMapper());
    }

    @Test
    void priceMin_alone_emitsLowerBound() {
        Filter f = builder.fromSlots(Map.of("priceMin", 500));

        assertThat(f.clause()).isEqualTo("price >= ?");
        assertThat(f.params()).containsExactly(500.0);
    }

    @Test
    void priceMin_withBudget_emitsBothBounds() {
        // LinkedHashMap-style ordering — fromSlots iterates known keys in fixed code order:
        // budget first, then priceMin
        Filter f = builder.fromSlots(Map.of("budget", 1000, "priceMin", 500));

        assertThat(f.clause()).isEqualTo("price <= ? AND price >= ?");
        assertThat(f.params()).containsExactly(1000.0, 500.0);
    }

    @Test
    void excludeProductIds_alone_emitsNotInClause() {
        Filter f = builder.fromSlots(Map.of("excludeProductIds", List.of(8821L, 8822L, 8823L)));

        assertThat(f.clause()).isEqualTo("id NOT IN (?, ?, ?)");
        assertThat(f.params()).containsExactly(8821L, 8822L, 8823L);
    }

    @Test
    void excludeProductIds_emptyList_isNoop() {
        Filter f = builder.fromSlots(Map.of("excludeProductIds", List.of()));

        assertThat(f.isEmpty()).isTrue();
    }

    @Test
    void excludeProductIds_coercesIntegersToLong() {
        // JSON deserialization typically produces Integer for small numeric ids;
        // builder must coerce to long so the JDBC binding matches BIGINT.
        Filter f = builder.fromSlots(Map.of("excludeProductIds", List.of(1, 2, 3)));

        assertThat(f.clause()).isEqualTo("id NOT IN (?, ?, ?)");
        assertThat(f.params()).containsExactly(1L, 2L, 3L);
    }

    @Test
    void priceMin_withExcludeIds_andBudget_combinesAllThree() {
        Filter f = builder.fromSlots(Map.of(
                "budget", 1000,
                "priceMin", 500,
                "excludeProductIds", List.of(10L, 20L)
        ));

        assertThat(f.clause()).isEqualTo("price <= ? AND price >= ? AND id NOT IN (?, ?)");
        assertThat(f.params()).containsExactly(1000.0, 500.0, 10L, 20L);
    }
}
