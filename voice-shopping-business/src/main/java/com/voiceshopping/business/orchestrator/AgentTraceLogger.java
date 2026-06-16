package com.voiceshopping.business.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Agent 调用链路 tracer：handle / streamHandle 入口生成 traceId 写入 MDC，
 * 所有 Agent / TTS 入口的日志自动带 traceId 前缀，
 * 通过 grep "TRACE <id>" 即可还原一轮对话的完整调用链。
 *
 * 用法：
 *   String traceId = AgentTraceLogger.startTrace(sessionId);
 *   try {
 *       // ... 业务逻辑
 *   } finally {
 *       AgentTraceLogger.endTrace();
 *   }
 *
 * Stage 枚举（约定，作为字符串传入）：
 *   STREAM_HANDLE / HANDLE / DISPATCH / RUN_REC / RUN_CLARIFY / RUN_COMPARE
 *   / INTENT / CLARIFY / EMOTION / EMOTION_STREAM / TTS / SENTENCE / TEXT_AUDIO
 */
public final class AgentTraceLogger {

    private static final Logger log = LoggerFactory.getLogger("AgentTrace");
    private static final String MDC_KEY = "traceId";

    /** 同一会话同一时刻同步链路上只有一条；流式链路在 streamHandle 入口生成 */
    public static String startTrace(String sessionId) {
        String traceId = sessionId + "#" + System.currentTimeMillis() % 100000;
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    public static void endTrace() {
        MDC.remove(MDC_KEY);
    }

    /** 用于响应式链路：从外部已知 traceId 恢复 MDC（在 Reactor 切线程后再次注入） */
    public static void resumeTrace(String traceId) {
        if (traceId != null) {
            MDC.put(MDC_KEY, traceId);
        }
    }

    public static String currentTraceId() {
        return MDC.get(MDC_KEY);
    }

    /** ENTER 日志：进入某个阶段 */
    public static void enter(String stage, String detail) {
        log.info("[TRACE {}] [{}] ENTER {}", currentOr("-"), stage, detail);
    }

    /** EXIT 日志：退出某个阶段（含耗时） */
    public static void exit(String stage, long elapsedMs, String detail) {
        log.info("[TRACE {}] [{}] EXIT  ({}ms) {}", currentOr("-"), stage, elapsedMs, detail);
    }

    /** 阶段内事件 */
    public static void event(String stage, String detail) {
        log.info("[TRACE {}] [{}] EVENT {}", currentOr("-"), stage, detail);
    }

    private static String currentOr(String fallback) {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : fallback;
    }

    private AgentTraceLogger() {}
}
