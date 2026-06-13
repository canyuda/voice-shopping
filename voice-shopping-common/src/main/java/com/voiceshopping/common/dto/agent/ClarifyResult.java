package com.voiceshopping.common.dto.agent;

import java.util.List;

/**
 * 需求澄清 Agent 的决策结果。
 *
 * @param action        ASK（需要追问）/ READY（槽位充足可就绪）
 * @param questionToAsk LLM 生成的追问文本，READY 时为 null
 * @param missingSlots  本轮追问的缺失槽位（已截断 ≤2），READY 时为空
 */
public record ClarifyResult(
        Action action,
        String questionToAsk,
        List<String> missingSlots
) {
    public enum Action { ASK, READY }

    public static ClarifyResult ready() {
        return new ClarifyResult(Action.READY, null, List.of());
    }

    public static ClarifyResult ask(String question, List<String> missing) {
        return new ClarifyResult(Action.ASK, question, missing);
    }
}
