package com.voiceshopping.business.order;

import com.voiceshopping.common.dto.agent.LastRecommendationsSnapshot;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link OrderReferenceResolver}. Each test maps to a
 * scenario in {@code order-placement-flow/spec.md}.
 * <p>
 * Helpers: {@link #snap(int)} builds a synthetic snapshot whose product ids
 * are {@code [10, 20, 30, ...]} (10 * (i + 1)) so assertions can be written
 * with hand-computed expected values rather than re-derived from the
 * production code.
 */
class OrderReferenceResolverTest {

    private final OrderReferenceResolver resolver = new OrderReferenceResolver();

    /**
     * Build a snapshot containing {@code n} items with product ids 10..n*10.
     * Prices are deliberate: id * 10 so {@code from} populates min/max in a
     * predictable way (not used by the resolver, but kept consistent).
     */
    private LastRecommendationsSnapshot snap(int n) {
        List<RecommendedItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            long id = (i + 1) * 10L;
            items.add(new RecommendedItem(
                    id,
                    "Item " + id,
                    BigDecimal.valueOf(id),
                    "推荐理由",
                    0.85,
                    Map.of()));
        }
        return LastRecommendationsSnapshot.from(items);
    }

    // ---------------- Scenario: 第二款映射到 index=1 ----------------
    @Test
    void chineseSecondOrdinal_mapsToIndex1() {
        assertThat(resolver.resolve(snap(3), "我要第二款"))
                .contains(20L);
    }

    // ---------------- Scenario: 阿拉伯数字与中文数字等价 ----------------
    @Test
    void arabicAndChineseOrdinalsAreEquivalent() {
        assertThat(resolver.resolve(snap(3), "就第3个吧")).contains(30L);
        assertThat(resolver.resolve(snap(3), "就第三个吧")).contains(30L);
    }

    // ---------------- Scenario: 倒数第二走"最后"语义而非 index=1 ----------------
    @Test
    void reverseQualifier_returnsLastNotIndexedItem() {
        assertThat(resolver.resolve(snap(3), "倒数第二的那个"))
                .contains(30L);
    }

    @Test
    void lastFirstQualifier_returnsLast() {
        assertThat(resolver.resolve(snap(3), "最后第一款"))
                .contains(30L);
    }

    // ---------------- Scenario: 单位词扩展支持"双" ----------------
    @Test
    void shoeUnitClassifier_supportedByOrdinalRegex() {
        assertThat(resolver.resolve(snap(2), "刚才那第一双"))
                .contains(10L);
    }

    // ---------------- Scenario: 越界返回 empty ----------------
    @Test
    void outOfRangeOrdinal_returnsEmpty() {
        assertThat(resolver.resolve(snap(2), "第五款")).isEmpty();
    }

    // ---------------- Scenario: lastRecommendations 为空返回 empty ----------------
    @Test
    void emptyProductIds_returnsEmpty() {
        LastRecommendationsSnapshot empty = LastRecommendationsSnapshot.EMPTY;
        assertThat(resolver.resolve(empty, "第一款")).isEmpty();
    }

    @Test
    void nullSnapshot_returnsEmpty() {
        assertThat(resolver.resolve(null, "第一款")).isEmpty();
    }

    // ---------------- Scenario: utterance 不含任何指代返回 empty ----------------
    @Test
    void unrelatedUtterance_returnsEmpty() {
        assertThat(resolver.resolve(snap(3), "我饿了")).isEmpty();
    }

    @Test
    void blankUtterance_returnsEmpty() {
        assertThat(resolver.resolve(snap(3), "")).isEmpty();
        assertThat(resolver.resolve(snap(3), null)).isEmpty();
    }

    // ---------------- Boundary: "第二天到" must NOT match ----------------
    @Test
    void unitClassifierWhitelistExcludesUnrelatedDigitContexts() {
        // "天" is not in the unit classifier list — should fall through to empty
        // when the rest of the utterance has no other indicators.
        assertThat(resolver.resolve(snap(3), "第二天到货吗")).isEmpty();
    }

    // ---------------- Boundary: "中间" parity ----------------
    @Test
    void middleOfOddSize_picksCenterIndex() {
        // size=3, idx=1, productId=20.
        assertThat(resolver.resolve(snap(3), "就要中间那款")).contains(20L);
    }

    @Test
    void middleOfEvenSize_picksLowerHalfIndex() {
        // size=4, size/2 = 2 (0-based), productId=30.
        // (Java integer division — documenting chosen semantics, not asserting
        // a particular UX preference.)
        assertThat(resolver.resolve(snap(4), "中间的")).contains(30L);
    }

    // ---------------- Boundary: position keywords ----------------
    @Test
    void firstKeyword_returnsFirst() {
        assertThat(resolver.resolve(snap(3), "开头那款")).contains(10L);
        assertThat(resolver.resolve(snap(3), "首款就好")).contains(10L);
    }

    @Test
    void lastKeyword_returnsLast() {
        assertThat(resolver.resolve(snap(3), "最后那款")).contains(30L);
        assertThat(resolver.resolve(snap(3), "末尾的")).contains(30L);
    }

    // ---------------- Boundary: ordinal upper bound ----------------
    @Test
    void tenAsTenthOrdinal() {
        // "十" → index 9 (10th item).
        // We only have 3 items, so this naturally falls out of range and
        // returns empty.
        assertThat(resolver.resolve(snap(3), "第十款")).isEmpty();
    }

    @Test
    void ordinalToIndex_coversAllDigits() {
        // Direct unit test of the index-mapping helper to keep the regex/
        // mapping decoupled.
        assertThat(resolver.ordinalToIndex("一")).isEqualTo(0);
        assertThat(resolver.ordinalToIndex("1")).isEqualTo(0);
        assertThat(resolver.ordinalToIndex("九")).isEqualTo(8);
        assertThat(resolver.ordinalToIndex("十")).isEqualTo(9);
        assertThat(resolver.ordinalToIndex("零")).isEqualTo(-1);
    }
}
