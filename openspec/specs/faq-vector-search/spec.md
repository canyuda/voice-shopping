# faq-vector-search

## Purpose

FAQ vector search capability: entity mapping, repository, similarity search with threshold filtering, and REST API endpoints for production and debug use.

## Requirements

### Requirement: FaqEntry Entity 映射 faq_entry 表
`FaqEntry` 实体类 SHALL 映射 `faq_entry` 表所有字段，包括 `embedding`（vector(1024)）和 `embedding_text`。JSONB 字段 `tags` 使用 JSON 类型映射。

#### Scenario: FaqEntry Entity 包含所有表字段
- **WHEN** 检查 FaqEntry 类的字段定义
- **THEN** 包含 id、merchantId、question、answer、category、tags、frequency、embedding、embeddingText、createdAt、updatedAt 共 11 个字段

### Requirement: FaqEntryRepository 提供基础查询
`FaqEntryRepository` SHALL 继承 `JpaRepository<FaqEntry, Long>`，并提供按 merchantId 查询的方法。

#### Scenario: 查询商家私有 + 平台通用 FAQ
- **WHEN** 调用 `findByMerchantIdIn(List.of(0L, merchantId))`
- **THEN** 返回平台通用（merchantId=0）和指定商家的所有 FAQ 条目

### Requirement: FaqVectorService.searchBest 相似度阈值过滤
`FaqVectorService` SHALL 提供 `searchBest(Long merchantId, String queryText, float[] queryVector)` 方法，返回 `Optional<FaqSearchResult>`。仅当最高相似度 >= 0.75 时返回结果，否则返回 `Optional.empty()`。

#### Scenario: 相似度达标返回结果
- **WHEN** 查询与某 FAQ 条目相似度 = 0.85
- **THEN** 返回 `Optional.of(FaqSearchResult)`，包含该 FAQ 的 question、answer、similarity=0.85

#### Scenario: 相似度不足返回空
- **WHEN** 查询与所有 FAQ 条目最高相似度 = 0.60
- **THEN** 返回 `Optional.empty()`

### Requirement: FaqVectorService 检索范围包含平台通用 FAQ
`FaqVectorService.searchBest` 的 SQL WHERE 条件 SHALL 包含 `merchant_id IN (0, :merchantId)`，同时检索平台通用和商家私有 FAQ。

#### Scenario: 平台通用 FAQ 被命中
- **WHEN** 商家 A（merchantId=1）的查询匹配到 merchantId=0 的平台通用 FAQ
- **THEN** 该 FAQ 条目出现在检索结果中

### Requirement: FaqSearchResult 返回 Record DTO
`FaqSearchResult` SHALL 为 Java Record，包含 id、question、answer、category、tags、similarity 字段。

#### Scenario: Record 包含完整 FAQ 信息
- **WHEN** FAQ 检索返回结果
- **THEN** FaqSearchResult 包含完整的 question/answer 及 similarity 分数

### Requirement: FaqController 提供 /api/v1/faq/ask 接口
`FaqController` SHALL 暴露 `POST /api/v1/faq/ask` 端点，接收 `{"merchantId": 1, "question": "怎么退换货"}` 请求体，返回匹配的 FAQ 答案或"未找到匹配"提示。

#### Scenario: FAQ 命中返回答案
- **WHEN** POST `/api/v1/faq/ask` 传入 `{"merchantId": 1, "question": "怎么退换货"}`
- **THEN** 返回 HTTP 200，body 包含 `{"found": true, "answer": "...", "similarity": 0.88}`

#### Scenario: FAQ 未命中返回未找到
- **WHEN** POST `/api/v1/faq/ask` 传入一个无关问题
- **THEN** 返回 HTTP 200，body 包含 `{"found": false, "answer": null, "similarity": 0.0}`

### Requirement: FaqController 提供 /api/v1/faq/ask-debug 调试接口
`FaqController` SHALL 暴露 `POST /api/v1/faq/ask-debug` 端点，与 `/ask` 逻辑相同但绕过 0.75 阈值过滤，返回 top-N 候选列表及每条的 similarity 分数，供运营标注和阈值调优。

#### Scenario: 调试接口返回 top-N 候选及相似度
- **WHEN** POST `/api/v1/faq/ask-debug` 传入 `{"merchantId": 1, "question": "退换货政策", "topN": 5}`
- **THEN** 返回 HTTP 200，body 包含 `{"results": [{"id": 12, "question": "...", "answer": "...", "similarity": 0.62}, ...]}`，即使相似度 < 0.75 也返回

#### Scenario: 默认 topN 为 5
- **WHEN** POST `/api/v1/faq/ask-debug` 不传 topN 参数
- **THEN** 默认返回 top 5 候选
