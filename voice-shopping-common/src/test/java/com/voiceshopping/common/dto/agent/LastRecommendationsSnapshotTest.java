package com.voiceshopping.common.dto.agent;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LastRecommendationsSnapshotTest {

    @Test
    void from_emptyList_returnsEmptySnapshot() {
        LastRecommendationsSnapshot snap = LastRecommendationsSnapshot.from(List.of());

        assertThat(snap.items()).isEmpty();
        assertThat(snap.minPrice()).isNull();
        assertThat(snap.maxPrice()).isNull();
        assertThat(snap.productIds()).isEmpty();
    }

    @Test
    void from_nullList_returnsEmptySnapshot() {
        LastRecommendationsSnapshot snap = LastRecommendationsSnapshot.from(null);

        assertThat(snap).isSameAs(LastRecommendationsSnapshot.EMPTY);
    }

    @Test
    void from_singleItem_minEqualsMax() {
        RecommendedItem item = new RecommendedItem(
                100L, "Asics GEL-Contend 9", new BigDecimal("479.00"),
                "缓震好", 0.88, Map.of());

        LastRecommendationsSnapshot snap = LastRecommendationsSnapshot.from(List.of(item));

        assertThat(snap.items()).hasSize(1);
        assertThat(snap.minPrice()).isEqualByComparingTo("479.00");
        assertThat(snap.maxPrice()).isEqualByComparingTo("479.00");
        assertThat(snap.productIds()).containsExactly(100L);
    }

    @Test
    void from_multipleItems_computesMinMaxAndIds() {
        RecommendedItem a = new RecommendedItem(
                1L, "A", new BigDecimal("479"), "r", 0.9, Map.of());
        RecommendedItem b = new RecommendedItem(
                2L, "B", new BigDecimal("599"), "r", 0.85, Map.of());
        RecommendedItem c = new RecommendedItem(
                3L, "C", new BigDecimal("379"), "r", 0.8, Map.of());

        LastRecommendationsSnapshot snap = LastRecommendationsSnapshot.from(List.of(a, b, c));

        assertThat(snap.items()).hasSize(3);
        assertThat(snap.minPrice()).isEqualByComparingTo("379");
        assertThat(snap.maxPrice()).isEqualByComparingTo("599");
        // productIds preserves original order
        assertThat(snap.productIds()).containsExactly(1L, 2L, 3L);
    }
}
