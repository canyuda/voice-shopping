package com.voiceshopping.business.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.voiceshopping.ai.agent.AgentFactory;
import com.voiceshopping.common.dto.agent.RecommendResult;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * EmotionAgent 的流式包装服务。
 * <p>
 * 基于 agent.stream() 返回字级 Flux，供 OrchestratorService.streamHandle()
 * 接入 SentenceAggregator → TTS 的流式管线。
 */
@Service
public class EmotionStreamingService {

    private static final Logger log = LoggerFactory.getLogger(EmotionStreamingService.class);

    private final AgentFactory agentFactory;
    private final SessionMoodDetector moodDetector;
    private final ObjectMapper objectMapper;
    private final AgentMemoryPolicy memoryPolicy;

    public EmotionStreamingService(AgentFactory agentFactory,
                                   SessionMoodDetector moodDetector,
                                   ObjectMapper objectMapper,
                                   AgentMemoryPolicy memoryPolicy) {
        this.agentFactory = agentFactory;
        this.moodDetector = moodDetector;
        this.objectMapper = objectMapper;
        this.memoryPolicy = memoryPolicy;
    }

    /**
     * 流式包装推荐结果为口语文本（字级 Flux）。
     *
     * @param sessionId     当前会话 id
     * @param userUtterance 用户原始发言
     * @param userNeeds     由 slots 转换的需求摘要
     * @param rec           推荐结果
     * @return 字级文本流
     */
    public Flux<String> streamWrap(String sessionId, String userUtterance,
                                   String userNeeds, RecommendResult rec) {
        com.voiceshopping.business.orchestrator.AgentTraceLogger.enter("EMOTION_STREAM",
                String.format("sessionId=%s, userNeeds=%s, recItems=%d, caller=%s",
                        sessionId, userNeeds,
                        rec != null && rec.items() != null ? rec.items().size() : 0,
                        callerInfo()));
        log.info("[EmotionStreamingService.streamWrap] ENTER sessionId={}, userUtterance={}, userNeeds={}, recItems={}, callStack={}",
                sessionId, userUtterance, userNeeds,
                rec != null && rec.items() != null ? rec.items().size() : 0,
                callerInfo());
        String mood = moodDetector.detect(sessionId, userUtterance);

        ReActAgent agent = agentFactory.getEmotionAgent(sessionId);
        memoryPolicy.beforeEmotionCall(agent);

        // 复用 EmotionService 的 userMsg 构建逻辑
        String userMsg = EmotionService.buildUserMsgStatic(userUtterance, mood, userNeeds, rec, objectMapper);
        log.info("[EmotionStreaming] LLM streaming request for session={}:\n{}", sessionId, userMsg);

        try {
            Msg userMessage = Msg.builder()
                    .role(MsgRole.USER)
                    .textContent(userMsg)
                    .build();

            // 流式起始时间，用于计算 AGENT_RESULT 到达时的总耗时
            final long llmT0 = System.currentTimeMillis();
            final int userMsgLen = userMsg.length();
            // 复制 sessionId/userId 到本地 final，给闭包用
            final String capturedSessionId = sessionId;

            // agent.stream() 返回 Flux<Event>，包含多种 EventType：
            // - REASONING + isLast=false : 字级增量（流式吐字）← 只要这个
            // - REASONING + isLast=true  : 最终完整文本重放（整段重放）✗ 必须过滤
            // - AGENT_RESULT             : 最终完整结果（整段重放）✗ 必须过滤；但作为成本埋点的 ChatUsage 来源
            // - SUMMARY/HINT 等          : 其他元数据事件
            // 必须同时过滤 isLast=true，否则最后那个完整重放会被当成新句子
            // 再 emit 一次到 SentenceAggregator，导致 TTS 把整段话再说一遍。
            return agent.stream(userMessage)
                    .doOnNext(event -> {
                        // 仅在 AGENT_RESULT 事件触发时埋点（这是流式总耗时和 token 统计的唯一时机）
                        if (event != null
                                && event.getType() == io.agentscope.core.agent.EventType.AGENT_RESULT) {
                            io.agentscope.core.model.ChatUsage usage =
                                    event.getMessage() != null ? event.getMessage().getChatUsage() : null;
                            String fullText = event.getMessage() != null
                                    ? event.getMessage().getTextContent() : null;
                            com.voiceshopping.common.cost.CostMetricsLogger.logLlm(
                                    "emotion_stream", "qwen-max",
                                    userMsgLen, fullText != null ? fullText.length() : 0,
                                    usage != null ? usage.getInputTokens() : null,
                                    usage != null ? usage.getOutputTokens() : null,
                                    usage != null ? usage.getTotalTokens() : null,
                                    System.currentTimeMillis() - llmT0, false);
                        }
                    })
                    .filter(event -> event != null
                            && event.getType() == io.agentscope.core.agent.EventType.REASONING
                            && !event.isLast())
                    .map(event -> {
                        Msg msg = event.getMessage();
                        String text = msg != null ? msg.getTextContent() : null;
                        return (text != null && !text.isBlank()) ? text : null;
                    })
                    .filter(text -> text != null)
                    .doOnComplete(() -> {
                        log.info("[EmotionStreaming] stream complete for session={}", sessionId);
                        com.voiceshopping.business.orchestrator.AgentTraceLogger.exit("EMOTION_STREAM",
                                0, "stream complete sessionId=" + sessionId);
                    })
                    .doOnError(e -> log.warn("[EmotionStreaming] stream error for session={}: {}",
                            sessionId, e.getMessage()))
                    .onErrorResume(e -> {
                        // 异常时返回 fallback 单元素流
                        log.warn("[EmotionStreaming] falling back for session={}: {}",
                                sessionId, e.getMessage());
                        return Flux.just(EmotionService.fallback(rec));
                    });
        } catch (Exception e) {
            log.warn("[EmotionStreaming] failed to start stream for session={}: {}",
                    sessionId, e.getMessage());
            return Flux.just(EmotionService.fallback(rec));
        }
    }

    /** 取调用栈中第一个非本类的栈帧，定位是谁调用了 streamWrap */
    private static String callerInfo() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement el : stack) {
            String cls = el.getClassName();
            if (!cls.startsWith("java.lang.Thread")
                    && !cls.equals(EmotionStreamingService.class.getName())) {
                return cls + "." + el.getMethodName() + ":" + el.getLineNumber();
            }
        }
        return "unknown";
    }
}
