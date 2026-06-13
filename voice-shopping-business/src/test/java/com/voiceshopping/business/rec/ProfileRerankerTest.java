package com.voiceshopping.business.rec;

import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.common.dto.agent.UserProfileSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileRerankerTest {

    private ProfileReranker reranker;

    @BeforeEach
    void setUp() {
        reranker = new ProfileReranker();
    }

    // --- helpers ---

    private RecommendedItem item(long id, double price, double score, Map<String, Object> attrs) {
        return new RecommendedItem(id, "Product-" + id, BigDecimal.valueOf(price), "", score, attrs);
    }

    private RecommendedItem item(long id, double price, double score) {
        return item(id, price, score, Map.of());
    }

    private UserProfileSnapshot profile(Map<String, Double> brandPrefs,
                                         String priceSensitivity,
                                         BigDecimal avgOrderAmount) {
        return new UserProfileSnapshot(
                1L, "MALE", "25-34", "Beijing",
                BigDecimal.valueOf(175), BigDecimal.valueOf(70), "42",
                "NORMAL", "MEDIUM", "300-500",
                Collections.emptyMap(), brandPrefs, priceSensitivity, avgOrderAmount, 5);
    }

    private UserProfileSnapshot defaultProfile() {
        return profile(Map.of(), "MEDIUM", BigDecimal.valueOf(300));
    }

    // --- Budget anchor ---

    @Nested
    @DisplayName("Budget anchor scoring")
    class BudgetAnchor {

        @Test
        @DisplayName("Main tier (60-95% of budget) gets +0.25")
        void mainTier() {
            var i = item(1, 350, 0.8); // 350/500 = 70%
            var slots = Map.<String, Object>of("budget", 500);
            double score = reranker.computeScore(i, defaultProfile(), slots);
            assertEquals(0.8 + 0.25, score, 0.001);
        }

        @Test
        @DisplayName("Mid tier (30-60% of budget) gets +0.05")
        void midTier_low() {
            var i = item(1, 180, 0.8); // 180/500 = 36%
            var slots = Map.<String, Object>of("budget", 500);
            double score = reranker.computeScore(i, defaultProfile(), slots);
            assertEquals(0.8 + 0.05, score, 0.001);
        }

        @Test
        @DisplayName("Mid tier (95-100% of budget) gets +0.05")
        void midTier_high() {
            var i = item(1, 490, 0.8); // 490/500 = 98%
            var slots = Map.<String, Object>of("budget", 500);
            double score = reranker.computeScore(i, defaultProfile(), slots);
            assertEquals(0.8 + 0.05, score, 0.001);
        }

        @Test
        @DisplayName("Far below budget (<30%) gets -0.1")
        void farBelow() {
            var i = item(1, 100, 0.8); // 100/500 = 20%
            var slots = Map.<String, Object>of("budget", 500);
            double score = reranker.computeScore(i, defaultProfile(), slots);
            assertEquals(0.8 - 0.10, score, 0.001);
        }

        @Test
        @DisplayName("No budget in slots → no adjustment")
        void noBudget() {
            var i = item(1, 350, 0.8);
            var slots = Map.<String, Object>of();
            double score = reranker.computeScore(i, defaultProfile(), slots);
            assertEquals(0.8, score, 0.001);
        }
    }

    // --- Brand preference ---

    @Nested
    @DisplayName("Brand preference scoring")
    class BrandPref {

        @Test
        @DisplayName("Preferred brand gets bonus")
        void preferredBrand() {
            var i = item(1, 400, 0.8, Map.of("brand", "Nike"));
            var p = profile(Map.of("Nike", 0.8), "MEDIUM", BigDecimal.valueOf(300));
            double score = reranker.computeScore(i, p, Map.of());
            assertEquals(0.8 + 0.8 * 0.2, score, 0.001);
        }

        @Test
        @DisplayName("Non-preferred brand gets no bonus")
        void nonPreferredBrand() {
            var i = item(1, 400, 0.8, Map.of("brand", "Adidas"));
            var p = profile(Map.of("Nike", 0.8), "MEDIUM", BigDecimal.valueOf(300));
            double score = reranker.computeScore(i, p, Map.of());
            assertEquals(0.8, score, 0.001);
        }

        @Test
        @DisplayName("Item without brand attribute gets no bonus")
        void noBrandAttr() {
            var i = item(1, 400, 0.8);
            var p = profile(Map.of("Nike", 0.8), "MEDIUM", BigDecimal.valueOf(300));
            double score = reranker.computeScore(i, p, Map.of());
            assertEquals(0.8, score, 0.001);
        }
    }

    // --- Price sensitivity ---

    @Nested
    @DisplayName("Price sensitivity scoring")
    class PriceSensitive {

        @Test
        @DisplayName("HIGH sensitivity + price > 1.5x avg gets -0.15")
        void highSensitiveOverBudget() {
            var i = item(1, 500, 0.8); // 500 > 300*1.5=450
            var p = profile(Map.of(), "HIGH", BigDecimal.valueOf(300));
            double score = reranker.computeScore(i, p, Map.of());
            assertEquals(0.8 - 0.15, score, 0.001);
        }

        @Test
        @DisplayName("HIGH sensitivity + price within range → no penalty")
        void highSensitiveWithinBudget() {
            var i = item(1, 400, 0.8); // 400 < 450
            var p = profile(Map.of(), "HIGH", BigDecimal.valueOf(300));
            double score = reranker.computeScore(i, p, Map.of());
            assertEquals(0.8, score, 0.001);
        }

        @Test
        @DisplayName("MEDIUM sensitivity → no penalty regardless of price")
        void mediumSensitive() {
            var i = item(1, 600, 0.8);
            var p = profile(Map.of(), "MEDIUM", BigDecimal.valueOf(300));
            double score = reranker.computeScore(i, p, Map.of());
            assertEquals(0.8, score, 0.001);
        }
    }

    // --- Recent purchase dedup ---

    @Nested
    @DisplayName("Recent purchase dedup (brand+category)")
    class RecentPurchase {

        @Test
        @DisplayName("Same brand + category gets -0.3")
        void sameBrandAndCategory() {
            var i = item(1, 400, 0.8, Map.of("brand", "Nike", "category", "跑鞋"));
            var slots = Map.<String, Object>of("brand", "Nike", "category", "跑鞋");
            double score = reranker.computeScore(i, defaultProfile(), slots);
            assertEquals(0.8 - 0.30, score, 0.001);
        }

        @Test
        @DisplayName("Different brand gets no penalty")
        void differentBrand() {
            var i = item(1, 400, 0.8, Map.of("brand", "Adidas", "category", "跑鞋"));
            var slots = Map.<String, Object>of("brand", "Nike", "category", "跑鞋");
            double score = reranker.computeScore(i, defaultProfile(), slots);
            assertEquals(0.8, score, 0.001);
        }

        @Test
        @DisplayName("No slot brand/category → no penalty")
        void noSlotContext() {
            var i = item(1, 400, 0.8, Map.of("brand", "Nike", "category", "跑鞋"));
            var slots = Map.<String, Object>of();
            double score = reranker.computeScore(i, defaultProfile(), slots);
            assertEquals(0.8, score, 0.001);
        }
    }

    // --- Multi-rule stacking ---

    @Nested
    @DisplayName("Multi-rule stacking")
    class Stacking {

        @Test
        @DisplayName("Budget main tier + brand preference stack")
        void budgetAndBrand() {
            var i = item(1, 350, 0.8, Map.of("brand", "Nike")); // 350/500 = 70%
            var p = profile(Map.of("Nike", 0.8), "MEDIUM", BigDecimal.valueOf(300));
            var slots = Map.<String, Object>of("budget", 500);
            double score = reranker.computeScore(i, p, slots);
            // 0.8 (base) + 0.25 (budget main) + 0.16 (brand)
            assertEquals(0.8 + 0.25 + 0.8 * 0.2, score, 0.001);
        }

        @Test
        @DisplayName("Budget low + price sensitive + recent purchase all penalize")
        void allPenalties() {
            var i = item(1, 100, 0.8, Map.of("brand", "Nike", "category", "跑鞋"));
            // 100/500 = 20% → budget low
            var p = profile(Map.of(), "HIGH", BigDecimal.valueOf(50)); // avg=50, 100>75
            var slots = Map.<String, Object>of("budget", 500, "brand", "Nike", "category", "跑鞋");
            double score = reranker.computeScore(i, p, slots);
            // 0.8 - 0.1 (budget low) - 0.15 (price sensitive) - 0.3 (recent)
            assertEquals(0.8 - 0.10 - 0.15 - 0.30, score, 0.001);
        }
    }

    // --- Null profile ---

    @Test
    @DisplayName("Null profile → only budget anchor applied")
    void nullProfile() {
        var i = item(1, 350, 0.8);
        var slots = Map.<String, Object>of("budget", 500);
        double score = reranker.computeScore(i, null, slots);
        assertEquals(0.8 + 0.25, score, 0.001);
    }

    // --- Rerank ordering ---

    @Test
    @DisplayName("Rerank sorts by adjusted score descending")
    void rerankOrdering() {
        var i1 = item(1, 350, 0.7, Map.of("brand", "Nike")); // +budget main + brand
        var i2 = item(2, 100, 0.9, Map.of());                 // +budget low
        var i3 = item(3, 400, 0.8, Map.of("brand", "Asics")); // +budget main

        var p = profile(Map.of("Nike", 0.8, "Asics", 0.5), "MEDIUM", BigDecimal.valueOf(300));
        var slots = Map.<String, Object>of("budget", 500);

        List<RecommendedItem> result = reranker.rerank(List.of(i1, i2, i3), p, slots);

        // i1: 0.7 + 0.25 + 0.16 = 1.11
        // i2: 0.9 - 0.10 = 0.80
        // i3: 0.8 + 0.25 + 0.10 = 1.15
        assertTrue(result.get(0).matchScore() >= result.get(1).matchScore());
        assertTrue(result.get(1).matchScore() >= result.get(2).matchScore());
    }

    @Test
    @DisplayName("Empty candidates returns empty list")
    void emptyCandidates() {
        var result = reranker.rerank(List.of(), defaultProfile(), Map.of());
        assertTrue(result.isEmpty());
    }
}
