## ADDED Requirements

### Requirement: EmotionService.wrap 调用埋点

`EmotionService.wrap`（同步路径）SHALL 在 LLM 调用结束后通过 `CostMetricsLogger.logLlm` 输出成本日志，agent 字段值固定为 `emotion`。

字段约束：
- `model`: 由 EmotionAgentBuilder 注入的模型名（当前为 `qwen-max`）
- `inputChars`: 喂给 LLM 的 userMsg 字符数（含 JSON 序列化后的全部内容）
- `outputChars`: LLM 返回 speechText 的字符数
- `inputTokens` / `outputTokens` / `totalTokens`: 来自 `Msg.getChatUsage()` 的精确值
- `cacheHit`: 固定为 `false`（EmotionService 不缓存）

埋点 MUST 在方法返回 EmotionResult 之前，无论是正常返回还是 fallback 兜底。

#### Scenario: 正常调用埋点
- **WHEN** EmotionService.wrap 成功调用 LLM 并解析输出
- **THEN** cost-metrics.log 出现 `scene=LLM agent=emotion model=qwen-max inputChars=<n> outputChars=<m> inputTokens=<x> outputTokens=<y> totalTokens=<z> durationMs=<ms> cacheHit=false`

#### Scenario: LLM 调用失败时埋点
- **WHEN** LLM 调用抛异常 / 输出解析失败 / 走 fallback 兜底
- **THEN** cost-metrics.log 仍输出该次调用的成本日志
- **THEN** 异常路径下 inputTokens/outputTokens/totalTokens MAY 缺失（如果 ChatUsage 不可得），其余字段照常输出

#### Scenario: 不与流式调用重复埋点
- **WHEN** runChitchat 等同步路径调用 EmotionService.wrap
- **THEN** 仅 EmotionService.wrap 出口产生一条 emotion 埋点
- **THEN** 同时不出现 emotion_stream 埋点（因为流式服务未被调）
