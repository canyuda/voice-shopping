## ADDED Requirements

### Requirement: 批量 reindex 管道触发接口
`AdminController` SHALL 暴露 `POST /api/v1/admin/reindex` 端点，触发 product 表全量向量重建。该接口为同步阻塞，执行完成后返回处理结果统计。

#### Scenario: 触发全量 reindex 成功
- **WHEN** POST `/api/v1/admin/reindex`
- **THEN** 系统读取所有 embedding_text 非空且 deleted_at IS NULL 的商品，调用 DashScope 批量向量化，更新 embedding 字段
- **THEN** 返回 HTTP 200，body 包含 `{"totalProcessed": 150, "successCount": 148, "failCount": 2}`

#### Scenario: 无需处理的商品
- **WHEN** 商品表中 embedding_text 非空的记录为 0
- **THEN** 返回 HTTP 200，body 包含 `{"totalProcessed": 0, "successCount": 0, "failCount": 0}`

### Requirement: reindex 并发分批处理
reindex 管道 SHALL 使用虚拟线程并发处理，每批 25 条商品调用 DashScope batch API，Semaphore 控制最大并发批次数（默认 5）。

#### Scenario: 100 条商品分 4 批并发处理
- **WHEN** 有 100 条商品需要向量化
- **THEN** 分为 4 批（每批 25 条），最多 5 个批次并发执行

### Requirement: reindex 单条失败不中断整体流程
当某条商品的向量化失败时，SHALL 记录失败并继续处理后续商品，最终在返回结果中统计成功和失败数量。

#### Scenario: 部分商品向量化失败
- **WHEN** 50 条商品中有 3 条因 DashScope API 错误向量化失败
- **THEN** 其余 47 条正常更新 embedding，返回 `{"totalProcessed": 50, "successCount": 47, "failCount": 3}`

### Requirement: 向量源文本构造规则
reindex 构造 embedding_text 时 SHALL 将商品 `name`、`category_l1`、`category_l2`、`description`、`selling_points`（加权）拼接为向量化源文本。`selling_points` 在拼接时重复一次以提升语义权重。

#### Scenario: 商品源文本构造
- **WHEN** 处理 id=8821 的商品（name="Asics GEL-Contend 9", categoryL1="鞋", categoryL2="跑鞋", description="GEL缓震", sellingPoints="轻量缓震"）
- **THEN** embedding_text 为 "Asics GEL-Contend 9 鞋 跑鞋 GEL缓震 轻量缓震 轻量缓震"

### Requirement: FAQ 批量 reindex 触发接口
`AdminController` SHALL 暴露 `POST /api/v1/admin/faq-reindex` 端点，触发 faq_entry 表全量向量重建。该接口为同步阻塞，执行完成后返回处理结果统计。

#### Scenario: 触发 FAQ 全量 reindex 成功
- **WHEN** POST `/api/v1/admin/faq-reindex`
- **THEN** 系统读取所有 embedding_text 非空的 FAQ 条目，调用 DashScope 批量向量化，更新 embedding 字段
- **THEN** 返回 HTTP 200，body 包含 `{"totalProcessed": 80, "successCount": 79, "failCount": 1}`

#### Scenario: 无需处理的 FAQ 条目
- **WHEN** faq_entry 表中 embedding_text 非空的记录为 0
- **THEN** 返回 HTTP 200，body 包含 `{"totalProcessed": 0, "successCount": 0, "failCount": 0}`

### Requirement: FAQ reindex 向量源文本构造规则
FAQ reindex 构造 embedding_text 时 SHALL 将 `question` + `answer` + `category`（如有）拼接为向量化源文本。

#### Scenario: FAQ 源文本构造
- **WHEN** 处理 id=1 的 FAQ（question="怎么退换货", answer="7天无理由退货", category="退换"）
- **THEN** embedding_text 为 "怎么退换货 7天无理由退货 退换"

### Requirement: FAQ reindex 并发分批与容错
FAQ reindex 管道 SHALL 复用与 product reindex 相同的并发分批策略（虚拟线程 + Semaphore + 每批 25 条），单条失败不中断整体流程。

#### Scenario: 部分 FAQ 向量化失败
- **WHEN** 30 条 FAQ 中有 2 条因 DashScope API 错误失败
- **THEN** 其余 28 条正常更新 embedding，返回 `{"totalProcessed": 30, "successCount": 28, "failCount": 2}`
