package com.voiceshopping.common.dto.agent;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Read-only user profile snapshot, assembled by {@code UserProfileService}
 * from static + dynamic profile data. Consumed by all Agents for personalization.
 *
 * @param userId          user ID
 * @param gender          MALE / FEMALE / OTHER / UNSPECIFIED
 * @param ageRange        e.g. "25-34"
 * @param city            user city
 * @param bodyHeight      height in cm
 * @param bodyWeight      weight in kg
 * @param shoeSize        shoe size
 * @param skinType        OILY / DRY / COMBINATION / SENSITIVE / NORMAL / UNSPECIFIED
 * @param techFamiliarity LOW / MEDIUM / HIGH
 * @param spendingRange   e.g. "300-500"
 * @param categoryPrefs   category preference scores {"跑鞋": 0.9}
 * @param brandPrefs      brand preference scores {"Nike": 0.8}
 * @param priceSensitivity LOW / MEDIUM / HIGH
 * @param avgOrderAmount  average order amount
 * @param purchaseCount   total purchase count
 */
public record UserProfileSnapshot(
        long userId,
        String gender,
        String ageRange,
        String city,
        BigDecimal bodyHeight,
        BigDecimal bodyWeight,
        String shoeSize,
        String skinType,
        String techFamiliarity,
        String spendingRange,
        Map<String, Double> categoryPrefs,
        Map<String, Double> brandPrefs,
        String priceSensitivity,
        BigDecimal avgOrderAmount,
        int purchaseCount
) {
}
