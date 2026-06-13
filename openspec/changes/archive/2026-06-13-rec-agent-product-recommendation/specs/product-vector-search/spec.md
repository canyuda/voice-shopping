## MODIFIED Requirements

### Requirement: ProductVectorService 余弦相似度检索
`ProductVectorService` SHALL 提供 `search(float[] queryVector, String extraFilter, List<Object> extraParams, int topK)` 方法，返回 `List<ProductSearchResult>`。

- 移除 `merchantId`、`minPrice`、`maxPrice`、`attributeFilters` 参数
- 新增 `extraFilter`（额外 WHERE 子句片段）和 `extraParams`（对应参数值）
- SQL 模板中 `merchant_id` 过滤处加 `-- TODO: add merchant_id filter for multi-tenancy` 注释，暂不加 merchant_id 条件
- `extraFilter` 非空时追加 `AND <extraFilter>` 到 WHERE 子句
- 同步修改 `/api/v1/search` 接口使用新签名

#### Scenario: 基础向量检索（无额外过滤）
- **WHEN** 调用 `search(queryVector, "", List.of(), 5)`
- **THEN** 返回最多 5 条结果，按余弦相似度降序排列，且 embedding 字段非空

#### Scenario: 带额外过滤的检索
- **WHEN** 调用 `search(queryVector, "price <= ? AND attributes @> CAST(? AS jsonb)", List.of(500, "{\"brand\":\"Nike\"}"), 10)`
- **THEN** SQL WHERE 包含 `price <= ?` 和 `attributes @> CAST(? AS jsonb)` 条件，返回最多 10 条结果

#### Scenario: 排除已删除商品
- **WHEN** 检索执行
- **THEN** SQL WHERE 条件包含 `deleted_at IS NULL`，不返回已软删除的商品

#### Scenario: extraFilter 为空时不追加条件
- **WHEN** 调用 `search(queryVector, "", List.of(), 5)`
- **THEN** SQL 不包含额外的 AND 条件
