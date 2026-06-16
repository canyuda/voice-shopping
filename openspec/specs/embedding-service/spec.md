# embedding-service

## Purpose

DashScope text-embedding-v3 wrapper providing single and batch text vectorization with retry, concurrency control, and fail-fast validation.

## Requirements

### Requirement: EmbeddingService 提供单条文本向量化
`EmbeddingService` SHALL 提供 `embed(String text)` 方法，调用 DashScope text-embedding-v3 模型，返回 `float[]`（维度 1024）。

#### Scenario: 单条文本成功向量化
- **WHEN** 调用 `embed("跑鞋 缓震 500元")`
- **THEN** 返回长度为 1024 的 float 数组，且数组元素不全为 0

#### Scenario: 空文本输入失败快速返回
- **WHEN** 调用 `embed("")` 或 `embed(null)`
- **THEN** 抛出 `IllegalArgumentException`

### Requirement: EmbeddingService 提供批量文本向量化
`EmbeddingService` SHALL 提供 `embedBatch(List<String> texts)` 方法，单次最多 25 条文本，返回 `List<float[]>`，顺序与输入一致。

#### Scenario: 批量文本成功向量化
- **WHEN** 调用 `embedBatch` 传入 25 条文本
- **THEN** 返回 25 个 float 数组，每个长度 1024

#### Scenario: 批量超过限制失败快速
- **WHEN** 调用 `embedBatch` 传入超过 25 条文本
- **THEN** 抛出 `IllegalArgumentException`

### Requirement: EmbeddingService API 调用失败时重试
当 DashScope API 返回限流或临时错误时，`EmbeddingService` SHALL 以指数退避策略重试，最多 3 次。

#### Scenario: 限流后重试成功
- **WHEN** DashScope 首次返回 429 状态码，第二次返回正常结果
- **THEN** 返回正常向量结果，不抛异常

#### Scenario: 重试耗尽后抛出异常
- **WHEN** DashScope 连续 3 次返回错误
- **THEN** 抛出 `EmbeddingException`，包含原始错误信息

### Requirement: EmbeddingService 并发控制
`EmbeddingService` SHALL 使用 Semaphore 限制最大并发 DashScope 调用数，默认值为 5，可通过配置 `voice-shopping.embedding.concurrency` 调整。

#### Scenario: 并发超限阻塞等待
- **WHEN** 同时发起 6 个 embed 调用（并发限制 5）
- **THEN** 第 6 个调用阻塞，直到前 5 个中有任一完成

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
