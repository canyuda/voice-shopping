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
`ProductVectorService` SHALL 提供 `search(Long merchantId, String queryText, float[] queryVector, int topK, BigDecimal minPrice, BigDecimal maxPrice, Map<String, Object> attributeFilters)` 方法，返回 `List<ProductSearchResult>`。

#### Scenario: 基础向量检索
- **WHEN** 调用 `search` 传入 merchantId=1, queryVector=[float[1024]], topK=5
- **THEN** 返回最多 5 条结果，按余弦相似度降序排列，且 embedding 字段非空

#### Scenario: 带价格区间过滤的检索
- **WHEN** 调用 `search` 传入 minPrice=100, maxPrice=500
- **THEN** 结果中所有商品的 price 在 [100, 500] 区间内

#### Scenario: 带 JSONB 属性过滤的检索
- **WHEN** 调用 `search` 传入 attributeFilters={"brand": "Asics"}
- **THEN** SQL WHERE 条件包含 `attributes @> '{"brand":"Asics"}'::jsonb`，结果只返回品牌为 Asics 的商品

#### Scenario: 排除已删除商品
- **WHEN** 检索执行
- **THEN** SQL WHERE 条件包含 `deleted_at IS NULL`，不返回已软删除的商品

### Requirement: ProductSearchResult 返回 Record DTO
`ProductSearchResult` SHALL 为 Java Record，包含 productId、name、categoryL1、categoryL2、price、imageUrls、attributes、similarity 字段。

#### Scenario: Record 包含相似度分数
- **WHEN** 向量检索返回结果
- **THEN** `similarity` 字段值等于 `1 - (embedding <=> query_vector)` 的计算结果，范围 [0, 1]
