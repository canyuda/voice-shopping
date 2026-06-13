package com.voiceshopping.business.rec;

import com.voiceshopping.common.dto.agent.RecommendedItem;
import com.voiceshopping.common.dto.agent.UserProfileSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reranks candidate products by combining cosine similarity with user profile
 * signals: budget anchor, brand preference, price sensitivity, and recent
 * purchase deduplication (brand+category match).
 */
@Component
public class ProfileReranker {

    private static final Logger log = LoggerFactory.getLogger(ProfileReranker.class);

    // Budget anchor thresholds
    private static final double BUDGET_MAIN_TIER_LOW = 0.60;
    private static final double BUDGET_MAIN_TIER_HIGH = 0.95;
    private static final double BUDGET_MID_TIER_LOW = 0.30;
    private static final double BUDGET_MID_TIER_HIGH = 1.00;

    // Score adjustments
    private static final double BONUS_BUDGET_MAIN = 0.25;
    private static final double BONUS_BUDGET_MID = 0.05;
    private static final double PENALTY_BUDGET_LOW = -0.10;
    private static final double BRAND_PREF_FACTOR = 0.20;
    private static final double PENALTY_PRICE_SENSITIVE = -0.15;
    private static final double PENALTY_RECENT_PURCHASE = -0.30;
    private static final double PRICE_SENSITIVE_RATIO = 1.5;

    /**
     * Rerank candidates by applying profile-based score adjustments.
     *
     * @param candidates list of items from candidate retrieval
     * @param profile    user profile snapshot (may be null for new users)
     * @param slots      recommendation slots with budget, category, brand, etc.
     * @return candidates sorted by adjusted matchScore descending
     */
    public List<RecommendedItem> rerank(List<RecommendedItem> candidates,
                                        UserProfileSnapshot profile,
                                        Map<String, Object> slots) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        return candidates.stream()
                .map(item -> item.withMatchScore(computeScore(item, profile, slots)))
                .sorted(Comparator.comparingDouble(RecommendedItem::matchScore).reversed())
                .toList();
    }

    /**
     * Compute adjusted score: original cosine similarity + profile bonuses/penalties.
     */
    double computeScore(RecommendedItem item, UserProfileSnapshot p, Map<String, Object> slots) {
        double score = item.matchScore();

        // 1. Budget anchor scoring
        score += budgetAnchorScore(item, slots);

        if (p == null) {
            return score;
        }

        // 2. Brand preference bonus
        score += brandPrefScore(item, p);

        // 3. Price sensitivity penalty
        score += priceSensitiveScore(item, p);

        // 4. Recent purchase dedup (brand+category match, downgraded from productId)
        score += recentPurchaseScore(item, p, slots);

        return score;
    }

    /**
     * Budget anchor: reward items priced in the 60-95% range of budget.
     */
    private double budgetAnchorScore(RecommendedItem item, Map<String, Object> slots) {
        Object budgetObj = slots.get("budget");
        if (!(budgetObj instanceof Number budgetNum)) {
            return 0;
        }
        double budget = budgetNum.doubleValue();
        if (budget <= 0) {
            return 0;
        }

        double price = item.price().doubleValue();
        double ratio = price / budget;

        if (ratio >= BUDGET_MAIN_TIER_LOW && ratio <= BUDGET_MAIN_TIER_HIGH) {
            log.trace("Budget main tier bonus for item {} (ratio={})", item.productId(), ratio);
            return BONUS_BUDGET_MAIN;
        }
        if (ratio >= BUDGET_MID_TIER_LOW && ratio <= BUDGET_MID_TIER_HIGH) {
            return BONUS_BUDGET_MID;
        }
        if (ratio < BUDGET_MID_TIER_LOW) {
            log.trace("Budget low tier penalty for item {} (ratio={})", item.productId(), ratio);
            return PENALTY_BUDGET_LOW;
        }
        return 0;
    }

    /**
     * Brand preference: add bonus proportional to brand affinity score.
     */
    private double brandPrefScore(RecommendedItem item, UserProfileSnapshot p) {
        Map<String, Double> brandPrefs = p.brandPrefs();
        if (brandPrefs == null || brandPrefs.isEmpty()) {
            return 0;
        }

        Object brand = item.attributes().get("brand");
        if (!(brand instanceof String brandName)) {
            return 0;
        }

        Double pref = brandPrefs.get(brandName);
        if (pref == null) {
            return 0;
        }

        log.trace("Brand pref bonus for item {}: {} × {}", item.productId(), pref, BRAND_PREF_FACTOR);
        return pref * BRAND_PREF_FACTOR;
    }

    /**
     * Price sensitivity: penalize items significantly above user's average order amount.
     */
    private double priceSensitiveScore(RecommendedItem item, UserProfileSnapshot p) {
        if (!"HIGH".equals(p.priceSensitivity())) {
            return 0;
        }

        BigDecimal avgAmount = p.avgOrderAmount();
        if (avgAmount == null || avgAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return 0;
        }

        double threshold = avgAmount.doubleValue() * PRICE_SENSITIVE_RATIO;
        double price = item.price().doubleValue();

        if (price > threshold) {
            log.trace("Price sensitive penalty for item {} (price={}, threshold={})",
                    item.productId(), price, threshold);
            return PENALTY_PRICE_SENSITIVE;
        }
        return 0;
    }

    /**
     * Recent purchase dedup: penalize items matching recently purchased brand+category.
     * Downgraded from productId matching due to recentBehavior lacking productId.
     */
    private double recentPurchaseScore(RecommendedItem item, UserProfileSnapshot p, Map<String, Object> slots) {
        // Use slots brand/category as proxy for user's recent purchase context
        Object slotBrand = slots.get("brand");
        Object slotCategory = slots.get("category");

        Object itemBrand = item.attributes().get("brand");

        // Match: same brand from slot and item attributes
        boolean brandMatch = slotBrand != null
                && itemBrand instanceof String ib
                && ib.equalsIgnoreCase(slotBrand.toString());

        // For category match, check categoryL1 from item or use slot category
        // Since RecommendedItem doesn't have category field, check attributes
        Object itemCategory = item.attributes().get("category");
        boolean categoryMatch = slotCategory != null
                && itemCategory != null
                && itemCategory.toString().equalsIgnoreCase(slotCategory.toString());

        // Only penalize when both brand AND category match (same brand in same category)
        if (brandMatch && categoryMatch) {
            log.trace("Recent purchase penalty for item {} (brand={}, category={})",
                    item.productId(), slotBrand, slotCategory);
            return PENALTY_RECENT_PURCHASE;
        }
        return 0;
    }
}
