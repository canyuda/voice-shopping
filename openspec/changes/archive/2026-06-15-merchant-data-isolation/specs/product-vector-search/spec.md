## MODIFIED Requirements

### Requirement: ProductVectorService 余弦相似度检索
`ProductVectorService` SHALL 提供 `search(float[] queryVector, String extraFilter, List<Object> extraParams, int topK)` 方法，返回 `List<ProductSearchResult>`。

- 参数 `extraFilter`（额外 WHERE 子句片段）和 `extraParams`（对应参数值）由**调用方**构造；`ProductVectorService` 自身不感知 sessionId / scope。
- **（本次修订）** 多商家隔离的实现路径在调用方层完成：上层（`RecommendOrchestrator` / `ParallelRecommendService` / `SearchController` 带 sessionId 的分支）负责通过 `ScopeFilterBuilder` + `SqlFilterBuilder.merge` 把 `merchant_id IN (...)` 子句拼进 `extraFilter`。`ProductVectorService.search` 方法体内 SHALL NOT 添加任何针对 `merchant_id` 的硬编码过滤。
- **（本次修订）** SQL 模板中原 `// TODO: add merchant_id filter for multi-tenancy` 注释 SHALL 替换为对调用契约的指引（如 `// merchant_id filter is supplied by callers via extraFilter (see ScopeFilterBuilder)`）。该 TODO 作为"上层强制叠加 scope"的契约承接结果，不再代表未实现的功能。
- `extraFilter` 非空时追加 `AND <extraFilter>` 到 WHERE 子句。
- SQL 使用 `embedding <=> ? AS distance` + `ORDER BY distance` 确保 HNSW 索引命中（避免 `1 - (...) DESC` 表达式包装导致全表扫描）。
- similarity 在 mapRow 中由 `1 - distance` 换算。
- `/api/v1/search` 接口使用此签名；该接口 SHALL 同步支持新增的 `sessionId` 可选 query 参数（具体语义见 `merchant-data-isolation` capability 中"SearchController 传 sessionId 叠加 scope"场景）。

#### Scenario: 基础向量检索（无额外过滤）
- **WHEN** 调用 `search(queryVector, "", List.of(), 5)`
- **THEN** 返回最多 5 条结果，按余弦相似度降序排列，且 embedding 字段非空

#### Scenario: 带额外过滤的检索（含 scope）
- **WHEN** 调用 `search(queryVector, "price <= ? AND merchant_id IN (?)", List.of(500, 5L), 10)`
- **THEN** SQL WHERE 包含 `price <= ?` 和 `merchant_id IN (?)` 条件，返回最多 10 条结果

#### Scenario: 排除已删除商品
- **WHEN** 检索执行
- **THEN** SQL WHERE 条件包含 `deleted_at IS NULL`，不返回已软删除的商品

#### Scenario: extraFilter 为空时不追加条件
- **WHEN** 调用 `search(queryVector, "", List.of(), 5)`
- **THEN** SQL 不包含额外的 AND 条件，且不包含 `merchant_id` 子句（因调用方未提供）

#### Scenario: SQL 模板不再含 merchant_id TODO
- **WHEN** 阅读 `ProductVectorService.search` 方法体
- **THEN** 不存在 `// TODO: add merchant_id filter for multi-tenancy` 字符串
