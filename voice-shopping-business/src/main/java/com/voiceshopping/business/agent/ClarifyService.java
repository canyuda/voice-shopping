package com.voiceshopping.business.agent;

import com.voiceshopping.ai.agent.AgentFactory;
import com.voiceshopping.common.dto.agent.ClarifyResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Orchestrates the clarify decision: rule check first, then LLM phrasing.
 * Orchestrator only needs to call {@link #decide(String, String, Map)}.
 */
@Service
public class ClarifyService {

    private static final Logger log = LoggerFactory.getLogger(ClarifyService.class);
    private static final int MAX_SLOTS_TO_ASK = 2;

    private final ClarifyRuleService ruleService;
    private final AgentFactory agentFactory;
    private final AgentMemoryPolicy memoryPolicy;
    private final ClarifyTemplateProperties templateProperties;

    public ClarifyService(ClarifyRuleService ruleService,
                          AgentFactory agentFactory,
                          AgentMemoryPolicy memoryPolicy,
                          ClarifyTemplateProperties templateProperties) {
        this.ruleService = ruleService;
        this.agentFactory = agentFactory;
        this.memoryPolicy = memoryPolicy;
        this.templateProperties = templateProperties;
    }

    /**
     * Decide whether to ask a clarifying question or proceed to recommendation.
     *
     * @param sessionId current session ID
     * @param utterance user's raw utterance for context
     * @param slots     currently extracted slots from intent agent
     * @return ASK with a natural question, or READY if slots are sufficient
     */
    public ClarifyResult decide(String sessionId, String utterance, Map<String, Object> slots) {
        long t0 = System.currentTimeMillis();
        com.voiceshopping.business.orchestrator.AgentTraceLogger.enter("CLARIFY",
                "sessionId=" + sessionId + ", slots=" + slots);
        try {
            return decideInternal(sessionId, utterance, slots, t0);
        } finally {
            com.voiceshopping.business.orchestrator.AgentTraceLogger.exit("CLARIFY",
                    System.currentTimeMillis() - t0, "");
        }
    }

    private ClarifyResult decideInternal(String sessionId, String utterance, Map<String, Object> slots, long t0) {
        // 1. Extract category
        String category = (String) slots.get("category");

        // 2. Rule check
        List<String> missingSlots = ruleService.missingSlots(category, slots);
        if (missingSlots.isEmpty()) {
            log.debug("All slots filled for category={}, ready to recommend", category);
            return ClarifyResult.ready();
        }

        // 3. 单字段模板优化：missingSlots.size() == 1 且模板表命中时，
        //    直接返回模板问句，跳过 LLM 调用（节省一次 qwen-turbo）。
        //    多字段或模板未命中仍走 LLM。
        if (missingSlots.size() == 1) {
            String slot = missingSlots.get(0);
            String template = templateProperties.singleSlotTemplates().get(slot);
            if (template != null && !template.isBlank()) {
                log.debug("Single slot template hit for slot={}, skipping LLM", slot);
                return ClarifyResult.ask(template, missingSlots);
            }
        }

        // 4. Truncate to avoid overwhelming the user (voice scenario)
        List<String> truncated = missingSlots.size() > MAX_SLOTS_TO_ASK
                ? missingSlots.subList(0, MAX_SLOTS_TO_ASK)
                : missingSlots;

        // 5. LLM generates natural follow-up question
        ReActAgent agent = agentFactory.getClarifyAgent(sessionId);
        memoryPolicy.beforeClarifyCall(agent);

        String userMsg = buildUserMsg(utterance, slots, truncated);
        log.info("[ClarifyAgent] LLM request for session={}:\n{}", sessionId, userMsg);

        Msg response = agent.call(
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(userMsg)
                        .build()
        ).block();

        String question = response != null ? response.getTextContent() : null;
        log.info("[ClarifyAgent] LLM response for session={}: {}", sessionId, question);

        // 成本埋点：从 ChatUsage 提取 token 数
        io.agentscope.core.model.ChatUsage usage = response != null ? response.getChatUsage() : null;
        com.voiceshopping.common.cost.CostMetricsLogger.logLlm(
                "clarify", "qwen-turbo",
                userMsg.length(), question != null ? question.length() : 0,
                usage != null ? usage.getInputTokens() : null,
                usage != null ? usage.getOutputTokens() : null,
                usage != null ? usage.getTotalTokens() : null,
                System.currentTimeMillis() - t0, false);

        if (question == null || question.isBlank()) {
            log.warn("ClarifyAgent returned empty for session={}, falling back to READY", sessionId);
            return ClarifyResult.ready();
        }

        return ClarifyResult.ask(question.trim(), truncated);
    }

    /**
     * Build the user message sent to the clarify LLM.
     * Formats known slots as bullet list, skips null values.
     */
    String buildUserMsg(String utterance, Map<String, Object> slots, List<String> missingSlots) {
        String knownSlotsText = slots.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .map(e -> "- " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));

        return "用户原话：" + utterance + "\n" +
                "已知信息：\n" + knownSlotsText + "\n" +
                "缺失字段：" + missingSlots;
    }
}
