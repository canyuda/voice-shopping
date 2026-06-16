## ADDED Requirements

### Requirement: EmbeddingService.embed 调用埋点

`EmbeddingService.embed` SHALL 在每次 DashScope embedding 调用结束后通过 `CostMetricsLogger.logEmbedding` 输出成本日志。

字段约束：
- `scene`: `EMBEDDING`
- `sessionId` / `userId`: 来自调用方传入（如果 embed 方法签名未含 sessionId/userId，可在调用 embed 的上层（如 ParallelRecommendService）埋点，或扩展 embed 签名）
- `model`: 当前模型名（如 `text-embedding-v3`）
- `inputChars`: query 字符数
- `inputTokens`: 来自 DashScope embedding API 响应的 `usage.total_tokens`（或 `prompt_tokens`，取 SDK 实际暴露的字段）；如果 SDK 不暴露则可缺省
- `durationMs`: 从入口到出口的总耗时

实现策略可二选一：
- **A.** 修改 `EmbeddingService.embed` 签名，新增 `sessionId/userId` 参数，由上层透传
- **B.** 在调用方（`ParallelRecommendService.recommend`）包裹埋点，不改 EmbeddingService

如果 DashScope embedding SDK 不暴露 token usage，`inputTokens` 字段 MAY 缺省（不输出）。

#### Scenario: 推荐链路触发 embedding 埋点
- **WHEN** ParallelRecommendService.recommend 中调用 embeddingService.embed("跑鞋 橡胶跑道 1000")
- **THEN** cost-metrics.log 出现 `scene=EMBEDDING sessionId=<id> userId=<id> model=text-embedding-v3 inputChars=<n> durationMs=<ms>`（inputTokens 视 SDK 暴露情况）

#### Scenario: 多次 embed 多条日志
- **WHEN** 同一会话内推荐链路触发多次 embed（如 fallback 重试不复用 vector，但当前实现复用 vector 不会重复 embed）
- **THEN** 每次 embed 调用产生一条独立的成本日志
