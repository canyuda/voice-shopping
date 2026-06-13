# product-vector-search

## Purpose

Product vector search capability: entity mapping with pgvector support, repository, vector upsert/write, cosine similarity search with JSONB attribute and price filters, and search result DTO.

## Requirements

### Requirement: Product Entity 映射 product 表
`Product` 实体类 SHALL 映射 `product` 表所有字段，包括 `embedding`（vector(1024)）和 `embedding_text`。JSONB 字段（`attributes`、`image_urls`）使用 hypersistence `@Type(JsonBinaryType.class)` 或 `@JdbcTypeCode(SqlTypes.JSON)` 映射。

#### Scenario: Product Entity 包含所有表字段
- **WHEN** 检查 Product 类的字段定义
- **THEN** 包含 id、merchantId、skuCode、name、categoryL1、categoryL2、isNewArrival、description、sellingPoints、price、originalPrice、imageUrls、attributes、status、embedding、embeddingText、deletedAt、createdAt、updatedAt 共 19 个业务字段

### Requirement: ProductRepository 提供基础 CRUD
`ProductRepository` SHALL 继承 `JpaRepository<Product, Long>`，并提供按 merchantId 和 status 查询的自定义方法。

#### Scenario: 按 merchantId 查询在售商品
- **WHEN** 调用 `findByMerchantIdAndStatusAndDeletedAtIsNull(merchantId, "ON_SALE")`
- **THEN** 返回该商家所有未删除的在售商品

#### Scenario: 按商品名模糊搜索
- **WHEN** 调用 `findByMerchantIdAndNameContainingAndDeletedAtIsNull(merchantId, keyword)`
- **THEN** 返回名称包含关键词的未删除商品

### Requirement: ProductVectorService 向量写入
`ProductVectorService` SHALL 提供 `upsertEmbedding(Long productId, float[] embedding, String embeddingText)` 方法，使用 JdbcTemplate 更新 product 表的 embedding 和 embedding_text 字段。

#### Scenario: 成功写入向量
- **WHEN** 调用 `upsertEmbedding(8821, float[1024], "跑鞋 Asics 缓震")`
- **THEN** product 表 id=8821 的 embedding 和 embedding_text 字段被更新

### Requirement: ProductVectorService 余弦相似度检索
`ProductVectorService` SHALL 提供 `search(float[] queryVector, String extraFilter, List<Object> extraParams, int topK)` 方法，返回 `List<ProductSearchResult>`。

- 移除 `merchantId`、`minPrice`、`maxPrice`、`attributeFilters` 参数
- 新增 `extraFilter`（额外 WHERE 子句片段）和 `extraParams`（对应参数值）
- SQL 模板中 `merchant_id` 过滤处加 `-- TODO: add merchant_id filter for multi-tenancy` 注释，暂不加 merchant_id 条件
- `extraFilter` 非空时追加 `AND <extraFilter>` 到 WHERE 子句
- SQL 使用 `embedding <=> ? AS distance` + `ORDER BY distance` 确保 HNSW 索引命中（避免 `1 - (...) DESC` 表达式包装导致全表扫描）
- similarity 在 mapRow 中由 `1 - distance` 换算
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

### Requirement: ProductSearchResult 返回 Record DTO
`ProductSearchResult` SHALL 为 Java Record，包含 productId、name、categoryL1、categoryL2、price、imageUrls、attributes、similarity 字段。

#### Scenario: Record 包含相似度分数
- **WHEN** 向量检索返回结果
- **THEN** `similarity` 字段值等于 `1 - (embedding <=> query_vector)` 的计算结果，范围 [0, 1]
