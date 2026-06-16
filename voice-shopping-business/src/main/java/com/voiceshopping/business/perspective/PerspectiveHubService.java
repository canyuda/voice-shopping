package com.voiceshopping.business.perspective;

import com.voiceshopping.ai.agent.AgentFactory;
import com.voiceshopping.ai.agent.AgentFactory.PerspectiveTeam;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.pipeline.MsgHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Side-channel three-role perspective discussion service.
 * <p>
 * Spins up a fresh {@link PerspectiveTeam} (price advisor / pro runner /
 * beginner buyer) and a {@link MsgHub} with auto-broadcast enabled, so that
 * each role can see prior speakers' content in its own memory and append
 * agreement/rebuttal naturally.
 * <p>
 * Failures degrade to an empty string — the side channel MUST NOT block the
 * main recommendation pipeline.
 */
@Service
public class PerspectiveHubService {

    private static final Logger log = LoggerFactory.getLogger(PerspectiveHubService.class);

    private final AgentFactory agentFactory;

    public PerspectiveHubService(AgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    /**
     * Run the three-role perspective discussion over the given Top-K items.
     *
     * @param sessionId active session id (used as MsgHub name suffix)
     * @param utterance user's original speech, included in the announcement
     * @param items     Top-K recommended items (typically 3); empty/null returns ""
     * @return formatted three-line text or "" on any failure / empty input
     */
    public String discuss(String sessionId, String utterance, List<RecommendedItem> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }

        try {
            PerspectiveTeam team = agentFactory.newPerspectiveTeam();

            Msg announcement = Msg.builder()
                    .name("host")
                    .role(MsgRole.USER)
                    .textContent(formatAnnouncement(utterance, items))
                    .build();

            try (MsgHub hub = MsgHub.builder()
                    .name("perspective_" + sessionId)
                    .participants(team.priceAgent(), team.proAgent(), team.beginnerAgent())
                    .announcement(announcement)
                    .enableAutoBroadcast(true)
                    .build()) {

                hub.enter().block();

                // Sequential no-arg call(): announcement is already in each agent's memory,
                // and auto-broadcast feeds prior speeches into subsequent agents' memory.
                log.debug("[perspective:{}] price agent speaking", sessionId);
                long priceT0 = System.currentTimeMillis();
                Msg priceMsg = team.priceAgent().call().block();
                logCost("perspective_price", priceMsg, System.currentTimeMillis() - priceT0);
                log.debug("[perspective:{}] price agent done", sessionId);

                log.debug("[perspective:{}] pro agent speaking", sessionId);
                long proT0 = System.currentTimeMillis();
                Msg proMsg = team.proAgent().call().block();
                logCost("perspective_pro", proMsg, System.currentTimeMillis() - proT0);
                log.debug("[perspective:{}] pro agent done", sessionId);

                log.debug("[perspective:{}] beginner agent speaking", sessionId);
                long beginnerT0 = System.currentTimeMillis();
                Msg beginnerMsg = team.beginnerAgent().call().block();
                logCost("perspective_beginner", beginnerMsg, System.currentTimeMillis() - beginnerT0);
                log.debug("[perspective:{}] beginner agent done", sessionId);

                return String.format("价格顾问：%s%n专业用户：%s%n入门买家：%s",
                        safeText(priceMsg), safeText(proMsg), safeText(beginnerMsg));
            }
        } catch (Exception e) {
            log.warn("Perspective discuss failed for sessionId={}, degrading to empty: {}",
                    sessionId, e.getMessage());
            return "";
        }
    }

    /**
     * 单个 perspective agent 调用结束后的成本埋点。
     */
    private static void logCost(String agent, Msg response, long durationMs) {
        if (response == null) {
            // 异常路径，token 字段缺省
            com.voiceshopping.common.cost.CostMetricsLogger.logLlm(
                    agent, "qwen-plus", 0, 0, null, null, null, durationMs, false);
            return;
        }
        io.agentscope.core.model.ChatUsage usage = response.getChatUsage();
        String text = response.getTextContent();
        com.voiceshopping.common.cost.CostMetricsLogger.logLlm(
                agent, "qwen-plus",
                0,  // perspective 的 inputChars 不易精确统计（announcement + memory 累计），暂留 0
                text != null ? text.length() : 0,
                usage != null ? usage.getInputTokens() : null,
                usage != null ? usage.getOutputTokens() : null,
                usage != null ? usage.getTotalTokens() : null,
                durationMs, false);
    }

    private String formatAnnouncement(String utterance, List<RecommendedItem> items) {
        StringBuilder products = new StringBuilder();
        for (RecommendedItem item : items) {
            products.append("- ")
                    .append(item.name())
                    .append(" / ")
                    .append(formatPrice(item.price()))
                    .append(" / ")
                    .append(item.reason() == null ? "" : item.reason())
                    .append('\n');
        }
        return String.format(
                "用户原话：%s%n%n待点评的 Top%d 商品：%n%s%n请各位依次发言，每人 30 字以内。",
                utterance, items.size(), products.toString().trim());
    }

    private String formatPrice(BigDecimal price) {
        return price == null ? "" : price.toPlainString();
    }

    private String safeText(Msg msg) {
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        return (text == null || text.isBlank()) ? "" : text;
    }
}
