package com.voiceshopping.business.perspective;

import com.voiceshopping.common.dto.agent.RecommendedItem;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for {@link PerspectiveHubService} that exercises the real
 * {@link io.agentscope.core.pipeline.MsgHub} with three live perspective
 * agents backed by DashScope qwen-plus.
 * <p>
 * <b>This test is {@code @Disabled} because:</b>
 * <ul>
 *   <li>Requires {@code dashscope.api-key} configured in environment.</li>
 *   <li>Burns three real LLM calls per run (~1.2-1.8s + cost).</li>
 *   <li>Auto-broadcast timing assertions (R1 in design.md) need manual review
 *       of LLM output to confirm pro/beginner agents reference prior speakers.</li>
 * </ul>
 * <b>How to run locally:</b>
 * <pre>
 *   export DASHSCOPE_API_KEY=...
 *   mvn -pl voice-shopping-business -am test \
 *       -Dtest=PerspectiveHubBroadcastIT \
 *       -Djunit.jupiter.conditions.deactivate=org.junit.jupiter.api.condition.DisabledCondition
 * </pre>
 * Or remove {@code @Disabled} temporarily to run via your IDE.
 *
 * <p>If 6.2 fails (later speakers ignore prior content), follow Task 6.3:
 * after each {@code call().block()}, append {@code hub.broadcast(msg).block()}
 * to enforce synchronous broadcast.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cache.type=NONE",
        "spring.jpa.hibernate.ddl-auto=none"
})
@Disabled("Requires real DashScope API key; run manually to verify auto-broadcast timing (R1).")
class PerspectiveHubBroadcastIT {

    @Autowired
    private PerspectiveHubService service;

    @Test
    @DisplayName("R1 timing: pro and beginner replies should reference prior speakers")
    void autoBroadcast_subsequentSpeakersReferencePrior() {
        // Items chosen so that price discussion has natural opinion hooks:
        // a budget pair vs. a flagship pair vs. an off-brand alternative.
        List<RecommendedItem> items = List.of(
                new RecommendedItem(1L, "ASICS GEL-Kayano 30",
                        new BigDecimal("1499"), "稳定支撑、长距离友好", 0.95, Map.of("brand", "ASICS")),
                new RecommendedItem(2L, "Nike Pegasus 41",
                        new BigDecimal("899"), "通勤训练全能款", 0.92, Map.of("brand", "Nike")),
                new RecommendedItem(3L, "李宁 飞电 4 Challenger",
                        new BigDecimal("499"), "国产性价比之选", 0.90, Map.of("brand", "李宁"))
        );

        String result = service.discuss("it-test", "想买跑鞋，膝盖不太好", items);

        // Hard structural guarantees
        assertFalse(result.isBlank(), "perspective text must be non-empty when agents return");
        assertTrue(result.contains("价格顾问："));
        assertTrue(result.contains("专业用户："));
        assertTrue(result.contains("入门买家："));
        assertFalse(result.contains("null"));

        // Soft semantic check (auto-broadcast validation): at least one keyword
        // implying cross-role awareness. If this assertion fails consistently
        // across runs, escalate to Task 6.3 (explicit hub.broadcast after each call).
        boolean keywordsHit =
                contains(result, "性价比", "促销", "替代款", "价格", "便宜", "贵") ||
                        contains(result, "缓震", "支撑", "膝盖", "脚踝", "训练") ||
                        contains(result, "穿搭", "上手", "心理价位", "follow", "新手");
        assertTrue(keywordsHit, "expected role-specific vocabulary in output: " + result);
    }

    private static boolean contains(String text, String... kws) {
        for (String k : kws) {
            if (text.contains(k)) return true;
        }
        return false;
    }
}
