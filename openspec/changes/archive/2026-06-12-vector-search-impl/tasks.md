## 1. Entity 与 Repository

- [x] 1.1 在 `voice-shopping-infrastructure` 的 `com.voiceshopping.infrastructure.repository.entity` 包下创建 `Product` 实体类，映射 product 表所有字段（含 embedding vector(1024)、JSONB 字段 image_urls/attributes）
- [x] 1.2 在 `voice-shopping-infrastructure` 的 `com.voiceshopping.infrastructure.repository.entity` 包下创建 `FaqEntry` 实体类，映射 faq_entry 表所有字段（含 embedding vector(1024)、JSONB 字段 tags）
- [x] 1.3 在 `com.voiceshopping.infrastructure.repository` 包下创建 `ProductRepository` 接口（extends JpaRepository），定义按 merchantId+status 查询、按商品名模糊搜索的方法
- [x] 1.4 在 `com.voiceshopping.infrastructure.repository` 包下创建 `FaqEntryRepository` 接口（extends JpaRepository），定义 `findByMerchantIdIn` 方法

## 2. EmbeddingService

- [x] 2.1 在 `com.voiceshopping.infrastructure.vector` 包下创建 `EmbeddingService` 类，注入 DashScope 配置（API Key 从 Spring 配置读取），实现 `embed(String text)` 单条向量化方法
- [x] 2.2 实现 `embedBatch(List<String> texts)` 批量向量化方法，限制单次最多 25 条，调用 DashScope text-embedding-v3 batch API
- [x] 2.3 添加指数退避重试逻辑（最多 3 次），处理 DashScope 限流和临时错误
- [x] 2.4 添加 Semaphore 并发控制，默认 5 并发，可通过 `voice-shopping.embedding.concurrency` 配置调整
- [x] 2.5 创建自定义异常类 `EmbeddingException`，封装 DashScope API 错误信息

## 3. ProductVectorService

- [x] 3.1 在 `com.voiceshopping.infrastructure.vector` 包下创建 `ProductSearchResult` Record DTO（productId, name, categoryL1, categoryL2, price, imageUrls, attributes, similarity）
- [x] 3.2 在 `com.voiceshopping.infrastructure.vector` 包下创建 `ProductVectorService` 类，注入 JdbcTemplate
- [x] 3.3 实现 `upsertEmbedding(Long productId, float[] embedding, String embeddingText)` 方法，用 PGobject 传递 vector 参数
- [x] 3.4 实现 `search(...)` 方法，构建原生 SQL：余弦相似度排序 + merchantId 过滤 + deleted_at IS NULL + 价格区间 + JSONB 属性过滤（`attributes @> ?::jsonb`）
- [x] 3.5 将 SQL 查询结果行映射为 `ProductSearchResult` Record

## 4. FaqVectorService 与 FaqController

- [x] 4.1 在 `com.voiceshopping.infrastructure.vector` 包下创建 `FaqSearchResult` Record DTO（id, question, answer, category, tags, similarity）
- [x] 4.2 在 `com.voiceshopping.infrastructure.vector` 包下创建 `FaqVectorService` 类，注入 JdbcTemplate
- [x] 4.3 实现 `searchBest(Long merchantId, String queryText, float[] queryVector)` 方法：SQL 查询 `merchant_id IN (0, ?)` + 余弦相似度排序 + 阈值 0.75 过滤，返回 `Optional<FaqSearchResult>`
- [x] 4.4 实现 `searchTopN(Long merchantId, float[] queryVector, int topN)` 方法：同 searchBest 但无阈值过滤，返回 `List<FaqSearchResult>`，供调试接口使用
- [x] 4.5 在 `com.voiceshopping.web.controller` 包下创建 `FaqController`，暴露 `POST /api/v1/faq/ask` 和 `POST /api/v1/faq/ask-debug` 端点，调用 EmbeddingService + FaqVectorService

## 5. Admin Reindex 与 Search 接口

- [x] 5.1 在 `com.voiceshopping.infrastructure.vector` 包下创建 `ReindexService`，实现全库批量向量化管道：分页读取商品 → 构造 embedding_text（selling_points 加重）→ 分批并发调用 EmbeddingService → upsertEmbedding 写入
- [x] 5.2 处理单条失败不中断整体流程，统计成功/失败数量，返回 `ReindexResult` Record
- [x] 5.3 在 `ReindexService` 中实现 FAQ 全量向量化管道：读取 faq_entry（question+answer+category 拼接 embedding_text）→ 分批并发调用 EmbeddingService → upsert embedding 字段
- [x] 5.4 在 `com.voiceshopping.web.controller` 包下创建 `ReindexController`，暴露 `POST /api/v1/admin/reindex` 和 `POST /api/v1/admin/faq-reindex` 端点
- [x] 5.5 在 `com.voiceshopping.web.controller` 包下创建 `SearchController`，暴露 `GET /api/v1/search` 端点（参数：q, topK, minPrice, maxPrice, attributes），调用 EmbeddingService + ProductVectorService

## 6. 配置与集成

- [x] 6.1 在 application.yml 中添加 DashScope embedding 相关配置（model 名称、并发数等），禁止硬编码
- [x] 6.2 确保 `voice-shopping-infrastructure` 模块 pom.xml 已包含 dashscope-sdk-java、pgvector 依赖
- [x] 6.3 验证 Spring Boot 启动正常，所有 bean 注册无冲突
