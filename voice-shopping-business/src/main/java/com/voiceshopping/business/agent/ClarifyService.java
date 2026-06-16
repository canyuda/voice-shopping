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

    public ClarifyService(ClarifyRuleService ruleService,
                          AgentFactory agentFactory,
                          AgentMemoryPolicy memoryPolicy) {
        this.ruleService = ruleService;
        this.agentFactory = agentFactory;
        this.memoryPolicy = memoryPolicy;
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
            return decideInternal(sessionId, utterance, slots);
        } finally {
            com.voiceshopping.business.orchestrator.AgentTraceLogger.exit("CLARIFY",
                    System.currentTimeMillis() - t0, "");
        }
    }

    private ClarifyResult decideInternal(String sessionId, String utterance, Map<String, Object> slots) {
        // 1. Extract category
        String category = (String) slots.get("category");

        // 2. Rule check
        List<String> missingSlots = ruleService.missingSlots(category, slots);
        if (missingSlots.isEmpty()) {
            log.debug("All slots filled for category={}, ready to recommend", category);
            return ClarifyResult.ready();
        }

        // 3. Truncate to avoid overwhelming the user (voice scenario)
        List<String> truncated = missingSlots.size() > MAX_SLOTS_TO_ASK
                ? missingSlots.subList(0, MAX_SLOTS_TO_ASK)
                : missingSlots;

        // 4. LLM generates natural follow-up question
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
