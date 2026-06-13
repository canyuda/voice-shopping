package com.voiceshopping.business.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.ai.agent.AgentFactory;
import com.voiceshopping.business.memory.ShortTermMemory;
import com.voiceshopping.common.dto.agent.IntentResult;
import com.voiceshopping.common.enums.IntentEnum;
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
 * Stateless service wrapping the intent agent.
 * Orchestrator calls {@link #classify(String, String)} without needing
 * to know anything about ReActAgent, memory management, or JSON parsing.
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private static final int HISTORY_TURNS = 3;

    private final AgentFactory agentFactory;
    private final ShortTermMemory shortTermMemory;
    private final IntentCache intentCache;
    private final ObjectMapper objectMapper;

    public IntentService(AgentFactory agentFactory,
                         ShortTermMemory shortTermMemory,
                         IntentCache intentCache,
                         ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.shortTermMemory = shortTermMemory;
        this.intentCache = intentCache;
        this.objectMapper = objectMapper;
    }

    /**
     * Classify user utterance into one of the six intents with extracted slots.
     *
     * @param sessionId current session ID (also used as cache scope)
     * @param utterance the user's latest utterance
     * @return parsed intent result, or OUT_OF_SCOPE with 0.3 confidence on failure
     */
    public IntentResult classify(String sessionId, String utterance) {
        // Build user input with recent history context
        String userInput = buildUserInput(sessionId, utterance);

        // Check fingerprint cache first (session-scoped)
        String cacheKey = IntentCache.computeHash(utterance, userInput);
        IntentResult cached = intentCache.get(sessionId, cacheKey);
        if (cached != null) {
            log.debug("Intent cache hit for session={}", sessionId);
            return cached;
        }

        ReActAgent agent = agentFactory.getIntentAgent(sessionId);

        // Clear previous turn's conversation history — intent is stateless
        agent.getMemory().clear();

        log.info("[IntentAgent] LLM request for session={}:\n{}", sessionId, userInput);

        Msg response = agent.call(
                Msg.builder()
                        .role(MsgRole.USER)
                        .textContent(userInput)
                        .build()
        ).block();

        if (response == null) {
            log.warn("Intent agent returned null for session={}, falling back to OUT_OF_SCOPE", sessionId);
            return new IntentResult(IntentEnum.OUT_OF_SCOPE, Map.of(), 0.3);
        }

        String rawText = response.getTextContent();
        log.info("[IntentAgent] LLM response for session={}: {}", sessionId, rawText);

        IntentResult result = parseIntent(rawText);

        // Cache non-fallback results to avoid repeated LLM calls
        if (result.intent() != IntentEnum.OUT_OF_SCOPE) {
            intentCache.put(sessionId, cacheKey, result);
        }

        return result;
    }

    /**
     * Format the last 3 conversation turns as history context.
     */
    private String buildUserInput(String sessionId, String utterance) {
        List<ShortTermMemory.Turn> recentTurns = shortTermMemory.recent(sessionId, HISTORY_TURNS);

        String historyText;
        if (recentTurns.isEmpty()) {
            historyText = "(无历史)";
        } else {
            historyText = recentTurns.stream()
                    .map(t -> t.role() + ": " + t.content())
                    .collect(Collectors.joining("\n"));
        }

        return """
                最近3条对话摘要：
                %s

                当前这一句：
                %s
                """.formatted(historyText, utterance);
    }

    /**
     * Extract the first outermost JSON object from LLM output (handles nested braces)
     * and parse to IntentResult. Falls back to OUT_OF_SCOPE on any parse failure.
     */
    private IntentResult parseIntent(String rawText) {
        String json = extractJson(rawText);
        if (json == null) {
            log.warn("No JSON found in intent agent response, falling back to OUT_OF_SCOPE");
            return new IntentResult(IntentEnum.OUT_OF_SCOPE, Map.of(), 0.3);
        }

        try {
            IntentJson parsed = objectMapper.readValue(json, IntentJson.class);
            return new IntentResult(
                    IntentEnum.valueOf(parsed.intent()),
                    parsed.slots() != null ? parsed.slots() : Map.of(),
                    parsed.confidence()
            );
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.warn("Failed to parse intent JSON, falling back to OUT_OF_SCOPE: {}", e.getMessage());
            return new IntentResult(IntentEnum.OUT_OF_SCOPE, Map.of(), 0.3);
        }
    }

    /**
     * Extracts the first complete JSON object from text, correctly handling
     * nested braces by tracking depth.
     *
     * @return the JSON substring including outer braces, or null if not found
     */
    static String extractJson(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null; // unbalanced braces
    }

    /**
     * Intermediate JSON structure matching the prompt's output format.
     * Ignores unknown fields so a flattened LLM output won't fail the parse.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record IntentJson(String intent, Map<String, Object> slots, double confidence) {
    }
}
