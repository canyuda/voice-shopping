package com.voiceshopping.business.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.ai.agent.AgentFactory;
import com.voiceshopping.common.dto.agent.EmotionResult;
import com.voiceshopping.common.dto.agent.RecommendResult;
import com.voiceshopping.common.dto.agent.RecommendedItem;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Wraps a recommendation result into a natural spoken reply for TTS.
 * <p>
 * Pipeline: detect session mood → build a lean user message (products carry
 * only name + reason, price/attributes stripped so the LLM cannot read out
 * prices) → call the emotion agent → parse the JSON {@code { "speechText": ... }}
 * → fallback on any failure.
 * <p>
 * {@code displayBlocks} always transparently passes through the full
 * {@code rec.items()}, so the frontend keeps price/attributes the voice
 * channel omits. The emotion agent's InMemoryMemory is preserved across turns
 * (not cleared) to keep conversational continuity, but trimmed to bound growth
 * since InMemoryMemory has no built-in cap.
 */
@Service
public class EmotionService {

    private static final Logger log = LoggerFactory.getLogger(EmotionService.class);

    /** Cap on cross-turn InMemoryMemory size; InMemoryMemory has no built-in trim. */
    private static final int MAX_EMOTION_MEMORY_MESSAGES = 10;

    private final AgentFactory agentFactory;
    private final SessionMoodDetector moodDetector;
    private final ObjectMapper objectMapper;

    public EmotionService(AgentFactory agentFactory,
                          SessionMoodDetector moodDetector,
                          ObjectMapper objectMapper) {
        this.agentFactory = agentFactory;
        this.moodDetector = moodDetector;
        this.objectMapper = objectMapper;
    }

    /**
     * Wrap a recommendation result into a spoken reply.
     *
     * @param sessionId      current session id
     * @param userUtterance  user's raw utterance
     * @param rec            recommendation result to wrap
     * @return EmotionResult with speechText (for TTS) and displayBlocks (for UI)
     */
    public EmotionResult wrap(String sessionId, String userUtterance, RecommendResult rec) {
        String mood = moodDetector.detect(sessionId, userUtterance);

        ReActAgent agent = agentFactory.getEmotionAgent(sessionId);
        trimMemoryIfNeeded(agent);

        String userMsg = buildUserMsg(userUtterance, mood, rec);
        log.info("[EmotionAgent] LLM request for session={}:\n{}", sessionId, userMsg);

        try {
            Msg response = agent.call(
                    Msg.builder()
                            .role(MsgRole.USER)
                            .textContent(userMsg)
                            .build()
            ).block();

            String rawText = response != null ? response.getTextContent() : null;
            log.info("[EmotionAgent] LLM response for session={}: {}", sessionId, rawText);

            String speech = parseSpeech(rawText);
            if (speech == null || speech.isBlank()) {
                log.warn("EmotionAgent produced empty speech for session={}, using fallback", sessionId);
                return new EmotionResult(fallback(rec), rec.items());
            }
            return new EmotionResult(speech, rec.items());
        } catch (Exception e) {
            log.warn("EmotionAgent call/parse failed for session={}, using fallback: {}", sessionId, e.getMessage());
            return new EmotionResult(fallback(rec), rec.items());
        }
    }

    /**
     * Build the JSON user message. products carry only name + reason so the
     * LLM cannot read out prices, keeping the spoken reply compliant and lean.
     */
    String buildUserMsg(String utterance, String mood, RecommendResult rec) {
        List<LeanProduct> products = rec.items().stream()
                .map(item -> new LeanProduct(nullSafe(item.name()), nullSafe(item.reason())))
                .toList();
        EmotionPromptInput input = new EmotionPromptInput(
                nullSafe(utterance), nullSafe(mood), nullSafe(rec.explanationTone()), products);
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            // a plain record of strings — should never fail to serialize
            throw new IllegalStateException("Failed to serialize emotion user message", e);
        }
    }

    /**
     * Extract the first JSON object and read speechText from it.
     * Returns null if no JSON is found or speechText is absent.
     */
    private String parseSpeech(String rawText) {
        if (rawText == null) {
            return null;
        }
        String json = IntentService.extractJson(rawText);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, SpeechJson.class).speechText();
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse emotion speechText JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fallback spoken reply when the LLM fails or returns nothing.
     * Empty items → gentle guidance; non-empty → list names + reasons.
     */
    static String fallback(RecommendResult rec) {
        if (rec.items() == null || rec.items().isEmpty()) {
            return "这个条件下合适的不多，要不要放宽点预算再看看？";
        }
        List<RecommendedItem> items = rec.items();
        String body = IntStream.range(0, items.size())
                .mapToObj(idx -> formatItemLine(idx + 1, items.get(idx)))
                .collect(Collectors.joining("\n"));
        return "好，给你挑了几款。\n" + body + "\n你看看选哪个？";
    }

    /** Format one fallback item line: "第i款: name" or "第i款: name, reason". */
    private static String formatItemLine(int ordinal, RecommendedItem item) {
        String name = nullSafe(item.name());
        String reason = nullSafe(item.reason());
        return reason.isBlank()
                ? "第" + ordinal + "款: " + name
                : "第" + ordinal + "款: " + name + ", " + reason;
    }

    /**
     * InMemoryMemory has no built-in cap; trim the oldest messages to bound
     * cross-turn growth while preserving recent continuity.
     */
    private void trimMemoryIfNeeded(ReActAgent agent) {
        int excess = agent.getMemory().getMessages().size() - MAX_EMOTION_MEMORY_MESSAGES;
        for (int i = 0; i < excess; i++) {
            agent.getMemory().deleteMessage(0);
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    /** Lean product view fed to the LLM — name + reason only. */
    private record LeanProduct(String name, String reason) {
    }

    /** Serialized as the JSON user message matching emotion.txt's expected input. */
    private record EmotionPromptInput(
            String userUtterance,
            String sessionMood,
            String explanationTone,
            List<LeanProduct> products
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SpeechJson(String speechText) {
    }
}
