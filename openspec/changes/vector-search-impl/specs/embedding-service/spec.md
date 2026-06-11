## ADDED Requirements

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
