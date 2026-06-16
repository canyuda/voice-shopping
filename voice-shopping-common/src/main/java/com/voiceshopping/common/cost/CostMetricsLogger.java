package com.voiceshopping.common.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * 成本埋点工具：单行 logfmt 格式输出 LLM/ASR/TTS/EMBEDDING 的调用消耗。
 * <p>
 * 日志输出到独立的 {@code CostMetrics} logger（logback 配置为独立文件
 * {@code logs/cost-metrics.log}，不污染主日志），方便外部工具通过
 * grep/awk/Loki 解析后生成成本看板。
 * <p>
 * 字段约定：
 * <pre>
 *   scene=LLM agent=emotion sessionId=xxx userId=101 model=qwen-max
 *   inputChars=234 outputChars=87 inputTokens=180 outputTokens=58
 *   totalTokens=238 durationMs=4200 cacheHit=false
 * </pre>
 * null 字段会被跳过（不输出 {@code field=null}）。
 * <p>
 * sessionId/userId 既支持显式传参，也支持自动从 MDC 读取（key 为
 * {@code costSessionId} / {@code costUserId}），避免改动各个服务的方法签名。
 */
public final class CostMetricsLogger {

    private static final Logger log = LoggerFactory.getLogger("CostMetrics");

    /** MDC key：会话 id（OrchestratorService 入口注入） */
    public static final String MDC_SESSION_ID = "costSessionId";
    /** MDC key：用户 id（OrchestratorService 入口注入） */
    public static final String MDC_USER_ID = "costUserId";

    private CostMetricsLogger() {
        throw new UnsupportedOperationException("CostMetricsLogger is a static utility class");
    }

    /**
     * 写入 MDC 上下文，供下游 service 内部埋点自动获取 sessionId/userId。
     * 调用方需在 try/finally 中配对 {@link #clearContext()} 清理 MDC。
     */
    public static void putContext(String sessionId, Long userId) {
        if (sessionId != null) {
            MDC.put(MDC_SESSION_ID, sessionId);
        }
        if (userId != null) {
            MDC.put(MDC_USER_ID, userId.toString());
        }
    }

    /** 清理 MDC 上下文（一定要在 finally 块调用） */
    public static void clearContext() {
        MDC.remove(MDC_SESSION_ID);
        MDC.remove(MDC_USER_ID);
    }

    private static String currentSessionId() {
        return MDC.get(MDC_SESSION_ID);
    }

    private static Long currentUserId() {
        String s = MDC.get(MDC_USER_ID);
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * LLM 调用埋点。sessionId/userId 自动从 MDC 读取。
     *
     * @param agent        子 agent 标签（intent / clarify / emotion / emotion_stream / perspective_*）
     * @param model        模型名（如 qwen-max / qwen-turbo）；缓存命中时可传 null
     * @param inputChars   输入字符数；缓存命中时可传 0（实际不输出）
     * @param outputChars  输出字符数；同上
     * @param inputTokens  来自 ChatUsage.inputTokens；缓存命中或 SDK 未返回时传 null
     * @param outputTokens 同上
     * @param totalTokens  同上
     * @param durationMs   方法入口到出口的总耗时（含 cache 查询/网络 IO）
     * @param cacheHit     是否命中缓存；命中时 inputTokens 等会被跳过
     */
    public static void logLlm(String agent,
                              String model, int inputChars, int outputChars,
                              Integer inputTokens, Integer outputTokens, Integer totalTokens,
                              long durationMs, boolean cacheHit) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("scene=LLM");
        appendField(sb, "agent", agent);
        appendField(sb, "sessionId", currentSessionId());
        appendField(sb, "userId", currentUserId());
        if (cacheHit) {
            // 缓存命中时只关心命中事实，token/字符统计无意义
            appendField(sb, "cacheHit", "true");
            appendField(sb, "durationMs", durationMs);
        } else {
            appendField(sb, "model", model);
            appendField(sb, "inputChars", inputChars);
            appendField(sb, "outputChars", outputChars);
            appendField(sb, "inputTokens", inputTokens);
            appendField(sb, "outputTokens", outputTokens);
            appendField(sb, "totalTokens", totalTokens);
            appendField(sb, "durationMs", durationMs);
            appendField(sb, "cacheHit", "false");
        }
        log.info(sb.toString());
    }

    /**
     * ASR 调用埋点（按音频时长计费的场景）。sessionId/userId 自动从 MDC 读取。
     */
    public static void logAsr(String model, long audioMs, long durationMs) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("scene=ASR");
        appendField(sb, "sessionId", currentSessionId());
        appendField(sb, "userId", currentUserId());
        appendField(sb, "model", model);
        appendField(sb, "audioMs", audioMs);
        appendField(sb, "durationMs", durationMs);
        log.info(sb.toString());
    }

    /**
     * TTS 调用埋点（按合成字符数计费的场景）。sessionId/userId 自动从 MDC 读取。
     */
    public static void logTts(String model, int inputChars, long durationMs) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("scene=TTS");
        appendField(sb, "sessionId", currentSessionId());
        appendField(sb, "userId", currentUserId());
        appendField(sb, "model", model);
        appendField(sb, "inputChars", inputChars);
        appendField(sb, "durationMs", durationMs);
        log.info(sb.toString());
    }

    /**
     * Embedding 调用埋点（按 input tokens 计费的场景）。sessionId/userId 自动从 MDC 读取。
     */
    public static void logEmbedding(String model, int inputChars, Integer inputTokens, long durationMs) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("scene=EMBEDDING");
        appendField(sb, "sessionId", currentSessionId());
        appendField(sb, "userId", currentUserId());
        appendField(sb, "model", model);
        appendField(sb, "inputChars", inputChars);
        appendField(sb, "inputTokens", inputTokens);
        appendField(sb, "durationMs", durationMs);
        log.info(sb.toString());
    }

    // ---- internal: logfmt encoding ----

    private static void appendField(StringBuilder sb, String key, Object value) {
        if (value == null) {
            return;
        }
        sb.append(' ').append(key).append('=');
        String str = value.toString();
        if (needsQuoting(str)) {
            sb.append('"');
            for (int i = 0; i < str.length(); i++) {
                char c = str.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\');
                }
                sb.append(c);
            }
            sb.append('"');
        } else {
            sb.append(str);
        }
    }

    /**
     * logfmt 引号规则：含空格/引号/`=` 或非常规可打印字符（含中文）需加引号。
     * 纯 ASCII 字母/数字 + 常用符号（_/-/./:/+/%/#）可裸输出。
     */
    private static boolean needsQuoting(String s) {
        if (s.isEmpty()) {
            return true;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.'
                    || c == ':' || c == '/' || c == '+'
                    || c == '%' || c == '#' || c == '@';
            if (!safe) {
                return true;
            }
        }
        return false;
    }
}

