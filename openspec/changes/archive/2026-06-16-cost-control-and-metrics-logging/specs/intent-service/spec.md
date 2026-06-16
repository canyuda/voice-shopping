## ADDED Requirements

### Requirement: IntentService 调用埋点

`IntentService.classify` SHALL 在每次调用结束时通过 `CostMetricsLogger.logLlm` 输出成本日志：
- 缓存命中（IntentCache 命中）：`cacheHit=true`，不输出 token 字段
- 缓存未命中：`cacheHit=false`，输出 model/inputChars/outputChars/inputTokens/outputTokens/totalTokens

埋点位置 MUST 在方法返回前，记录的 durationMs MUST 包含 cache 查询 + LLM 调用的总耗时（即从入口到出口）。

#### Scenario: 缓存命中埋点
- **WHEN** IntentCache 命中并返回缓存的 IntentResult
- **THEN** cost-metrics.log 出现 `scene=LLM agent=intent sessionId=<id> userId=<id> cacheHit=true durationMs=<ms>`
- **THEN** 不输出 inputTokens/outputTokens/model 字段

#### Scenario: 缓存未命中埋点
- **WHEN** IntentCache 未命中，调用 IntentAgent (qwen-turbo) 完成分类
- **THEN** cost-metrics.log 出现 `scene=LLM agent=intent ... cacheHit=false model=qwen-turbo inputTokens=<n> outputTokens=<m> totalTokens=<t>`
- **THEN** tokens 来自 `Msg.getChatUsage()` 的精确值（不估算）

#### Scenario: agent name 固定为 intent
- **WHEN** IntentService.classify 埋点
- **THEN** agent 字段值固定为字符串 `intent`

#### Scenario: durationMs 含缓存查询时间
- **WHEN** 缓存命中场景
- **THEN** durationMs 反映从方法入口到 cache.get() 返回的实际时间（通常 <10ms）
