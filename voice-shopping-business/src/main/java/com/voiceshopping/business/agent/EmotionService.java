package com.voiceshopping.business.agent;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 将推荐结果包装为自然的口语音频回复。
 * <p>
 * 流程：检测会话情绪 → 构建含 userNeeds + 商品裸数据的 userMsg → 调用 EmotionAgent
 * → 解析纯文本输出（兜底 markdown 包裹） → 失败时走 fallback。
 * <p>
 * displayBlocks 始终透传完整的 rec.items()，前端保留 price/attributes 等语音通道
 * 省略的字段。EmotionAgent 的 InMemoryMemory 跨轮保留，不做 clear。
 */
@Service
public class EmotionService {

    private static final Logger log = LoggerFactory.getLogger(EmotionService.class);

    private final AgentFactory agentFactory;
    private final SessionMoodDetector moodDetector;
    private final ObjectMapper objectMapper;
    private final AgentMemoryPolicy memoryPolicy;

    public EmotionService(AgentFactory agentFactory,
                          SessionMoodDetector moodDetector,
                          ObjectMapper objectMapper,
                          AgentMemoryPolicy memoryPolicy) {
        this.agentFactory = agentFactory;
        this.moodDetector = moodDetector;
        this.objectMapper = objectMapper;
        this.memoryPolicy = memoryPolicy;
    }

    /**
     * 将推荐结果包装为口语音频回复。
     *
     * @param sessionId      当前会话 id
     * @param userUtterance  用户原始发言
     * @param userNeeds      由 slots 转换的需求摘要（格式：key1=val1,key2=val2,...）
     * @param rec            推荐结果
     * @return EmotionResult，含 speechText（TTS）和 displayBlocks（UI 商品卡片）
     */
    public EmotionResult wrap(String sessionId, String userUtterance,
                              String userNeeds, RecommendResult rec) {
        long t0 = System.currentTimeMillis();
        com.voiceshopping.business.orchestrator.AgentTraceLogger.enter("EMOTION",
                String.format("sessionId=%s, userNeeds=%s, recItems=%d, caller=%s",
                        sessionId, userNeeds,
                        rec != null && rec.items() != null ? rec.items().size() : 0,
                        callerInfo()));
        try {
            return wrapInternal(sessionId, userUtterance, userNeeds, rec);
        } finally {
            com.voiceshopping.business.orchestrator.AgentTraceLogger.exit("EMOTION",
                    System.currentTimeMillis() - t0, "");
        }
    }

    private EmotionResult wrapInternal(String sessionId, String userUtterance,
                                        String userNeeds, RecommendResult rec) {
        log.info("[EmotionService.wrap] ENTER sessionId={}, userUtterance={}, userNeeds={}, recItems={}, callStack={}",
                sessionId, userUtterance, userNeeds,
                rec != null && rec.items() != null ? rec.items().size() : 0,
                callerInfo());
        String mood = moodDetector.detect(sessionId, userUtterance);

        ReActAgent agent = agentFactory.getEmotionAgent(sessionId);
        memoryPolicy.beforeEmotionCall(agent);

        String userMsg = buildUserMsg(userUtterance, mood, userNeeds, rec);
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
     * 构建喂给 LLM 的 JSON userMsg。
     * products 传裸数据（含 name/price/attributes），让 EmotionAgent 基于
     * 真实属性生成推荐理由；去掉 explanationTone，新增 userNeeds。
     */
    String buildUserMsg(String utterance, String mood, String userNeeds, RecommendResult rec) {
        List<MergedProduct> products = rec.items().stream()
                .map(item -> new MergedProduct(
                        item.productId(),
                        nullSafe(item.name()),
                        item.price(),
                        item.attributes() != null ? item.attributes() : Map.of()
                ))
                .toList();
        EmotionPromptInput input = new EmotionPromptInput(
                nullSafe(utterance), nullSafe(mood), nullSafe(userNeeds), products);
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            // 纯 record 序列化不应失败
            throw new IllegalStateException("Failed to serialize emotion user message", e);
        }
    }

    /**
     * 静态版本，供 EmotionStreamingService 复用 userMsg 构建逻辑。
     */
    static String buildUserMsgStatic(String utterance, String mood, String userNeeds,
                                     RecommendResult rec, ObjectMapper objectMapper) {
        List<MergedProduct> products = rec.items().stream()
                .map(item -> new MergedProduct(
                        item.productId(),
                        nullSafe(item.name()),
                        item.price(),
                        item.attributes() != null ? item.attributes() : Map.of()
                ))
                .toList();
        EmotionPromptInput input = new EmotionPromptInput(
                nullSafe(utterance), nullSafe(mood), nullSafe(userNeeds), products);
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize emotion user message", e);
        }
    }

    /**
     * 解析 EmotionAgent 的纯文本输出。
     * 新 prompt 输出纯文本，不再有 JSON 包裹；
     * 兜底处理模型偶发的 ```json ... ``` 包裹。
     */
    private String parseSpeech(String rawText) {
        if (rawText == null) {
            return null;
        }
        // 去掉 markdown code fence 包裹
        String cleaned = rawText
                .replaceAll("^```(?:json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
        // 去掉换行（prompt 约束不换行，但兜底一下）
        cleaned = cleaned.replaceAll("[\\r\\n]+", "");
        return cleaned.isBlank() ? null : cleaned;
    }

    /**
     * LLM 失败或返回空时的兜底口语回复。
     * 无商品 → 温和引导；有商品 → 列出名称。
     */
    public static String fallback(RecommendResult rec) {
        if (rec.items() == null || rec.items().isEmpty()) {
            return "这个条件下合适的不多，要不要放宽点预算再看看？";
        }
        List<RecommendedItem> items = rec.items();
        String body = IntStream.range(0, items.size())
                .mapToObj(idx -> formatItemLine(idx + 1, items.get(idx)))
                .collect(Collectors.joining("\n"));
        return "好，给你挑了几款。\n" + body + "\n你看看选哪个？";
    }

    /** 格式化单条兜底商品行："第i款: name" */
    private static String formatItemLine(int ordinal, RecommendedItem item) {
        String name = nullSafe(item.name());
        return "第" + ordinal + "款: " + name;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    /** 取调用栈中第一个非本类的栈帧，定位是谁调用了 wrap */
    private static String callerInfo() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement el : stack) {
            String cls = el.getClassName();
            if (!cls.startsWith("java.lang.Thread")
                    && !cls.equals(EmotionService.class.getName())) {
                return cls + "." + el.getMethodName() + ":" + el.getLineNumber();
            }
        }
        return "unknown";
    }

    /** 喂给合并 prompt 的商品视图，含属性数据 */
    record MergedProduct(
            Long productId,
            String name,
            BigDecimal price,
            Map<String, Object> attributes
    ) {
    }

    /** 序列化为 JSON，匹配 emotion-merged.txt 的输入格式 */
    private record EmotionPromptInput(
            String userUtterance,
            String sessionMood,
            String userNeeds,
            List<MergedProduct> products
    ) {
    }
}
