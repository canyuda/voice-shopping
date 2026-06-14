package com.voiceshopping.business.compliance;

import com.voiceshopping.common.dto.agent.EmotionResult;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link ComplianceChecker} rewrite rules.
 * <p>
 * Bypasses Spring's classpath resource loading by injecting the sensitive-words
 * set directly via {@link ReflectionTestUtils} — keeps the test free of any
 * test-only resource files. Hand-computed expected strings; no production-code
 * round-tripping in expectations.
 */
class ComplianceCheckerTest {

    private ComplianceChecker checker;

    @BeforeEach
    void setup() {
        Map<String, String> patterns = new LinkedHashMap<>();
        patterns.put("最便宜", "性价比高");
        patterns.put("第一", "表现突出");

        ComplianceProperties props = new ComplianceProperties();
        props.setAbsoluteClaimPatterns(patterns);
        checker = new ComplianceChecker(props);
        // Inject sensitive words without invoking @PostConstruct's classpath load.
        ReflectionTestUtils.setField(checker, "sensitiveWords", Set.of("黑心", "假货"));
    }

    @Test
    void absoluteClaim_isReplacedByConfiguredReplacement() {
        EmotionResult input = new EmotionResult("这款是最便宜的", List.of());

        EmotionResult out = checker.ensureCompliant("s1", 1L, input);

        assertThat(out.speechText()).isEqualTo("这款是性价比高的");
    }

    @Test
    void sensitiveWord_isMaskedWithSameLengthOfStars() {
        EmotionResult input = new EmotionResult("这家店黑心", List.of());

        EmotionResult out = checker.ensureCompliant("s1", 1L, input);

        // "黑心" is 2 chars → "**"
        assertThat(out.speechText()).isEqualTo("这家店**");
    }

    @Test
    void absoluteFirst_thenSensitive_ordered() {
        // Both rules trigger in one reply. Order matters because absolute-claim
        // rewrite happens before sensitive masking (per the spec).
        EmotionResult input = new EmotionResult("这家店最便宜也是黑心", List.of());

        EmotionResult out = checker.ensureCompliant("s1", 1L, input);

        assertThat(out.speechText()).isEqualTo("这家店性价比高也是**");
    }

    @Test
    void multipleOccurrences_allReplaced() {
        EmotionResult input = new EmotionResult("假货店全是假货", List.of());

        EmotionResult out = checker.ensureCompliant("s1", 1L, input);

        assertThat(out.speechText()).isEqualTo("**店全是**");
    }

    @Test
    void cleanText_returnsSameInstance_unchanged() {
        EmotionResult input = new EmotionResult("这双跑鞋缓震不错", List.of());

        EmotionResult out = checker.ensureCompliant("s1", 1L, input);

        // No rewrite happened → spec says return same EmotionResult.
        assertThat(out).isSameAs(input);
    }

    @Test
    void rewrittenResult_isFreshInstance_displayBlocksPreserved() {
        List<RecommendedItem> blocks = List.of(
                new RecommendedItem(1L, "A", new BigDecimal("100"), "r", 0.9, Map.of()));
        EmotionResult input = new EmotionResult("这家黑心", blocks);

        EmotionResult out = checker.ensureCompliant("s1", 1L, input);

        // New instance after a rewrite (mandated by "返回新的 EmotionResult 对象").
        assertThat(out).isNotSameAs(input);
        // Display blocks pass through unchanged.
        assertThat(out.displayBlocks()).isSameAs(blocks);
    }
}
