package com.voiceshopping.business.agent;

import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmotionServiceFallbackTest {

    private RecommendedItem item(String name, String reason) {
        return new RecommendedItem(1L, name, BigDecimal.valueOf(100), reason, 0.9, Map.of());
    }

    @Test
    void fallback_emptyItems_returnsGuidance() {
        String speech = EmotionService.fallback(new RecommendResult(List.of(), "empty"));
        assertTrue(speech.contains("合适的不多"));
        assertTrue(speech.contains("放宽"));
    }

    @Test
    void fallback_nonEmptyItems_listsNamesAndReasons() {
        var rec = new RecommendResult(
                List.of(item("Nike跑鞋", "缓震好"), item("Asics", "支撑稳")),
                "professional");
        String speech = EmotionService.fallback(rec);

        assertTrue(speech.startsWith("好，给你挑了几款。"));
        assertTrue(speech.contains("Nike跑鞋"));
        assertTrue(speech.contains("缓震好"));
        assertTrue(speech.contains("Asics"));
        assertTrue(speech.endsWith("你看看选哪个？"));
    }

    @Test
    void fallback_blankReason_omitsReason() {
        var rec = new RecommendResult(
                List.of(item("OnlyName", ""), item("EmptyReason", null)),
                "professional");
        String speech = EmotionService.fallback(rec);

        assertTrue(speech.contains("第1款: OnlyName\n"));
        assertTrue(speech.contains("第2款: EmptyReason\n"));
        assertFalse(speech.contains("OnlyName,"));
        assertFalse(speech.contains("EmptyReason,"));
    }
}
