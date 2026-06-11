## Why

语音购物系统的 RecAgent 和 FAQ 检索依赖 pgvector 向量语义匹配，当前 `product` 和 `faq_entry` 两张表的 Entity/Repository 层、向量写入/检索服务、批量索引管道均未实现。向量检索是推荐 Agent 和 FAQ Agent 的核心数据通道，属于 P0 阻塞项。

## What Changes

- 新增 `product` 表和 `faq_entry` 表的 JPA Entity（含 pgvector `vector(1024)` 字段映射）
- 新增对应 Repository 接口
- 新增 `EmbeddingService`，封装 DashScope text-embedding-v3 调用，支持单条和批量（batch）向量生成
- 新增 `ProductVectorService`，基于 JdbcTemplate 实现：
  - 向量写入（单条 upsert embedding）
  - 向量检索（余弦相似度 + JSONB 属性过滤 + 价格区间过滤）
- 新增 `POST /api/v1/admin/reindex` 管理接口，触发全库商品批量向量化（并发分批调用 DashScope batch API）
- 新增 `POST /api/v1/admin/faq-reindex` 管理接口，触发全库 FAQ 批量向量化
- 新增 `GET /api/v1/search` 检索接口，作为向量检索的 smoke test
- 新增 `FaqVectorService`，提供 `searchBest` 方法（相似度阈值 ≥ 0.75，低于阈值返回空，走 LLM 兜底）
- 新增 `POST /api/v1/faq/ask` 接口，对外暴露 FAQ 问答

## Capabilities

### New Capabilities
- `embedding-service`: DashScope text-embedding-v3 向量化服务，支持单条与批量调用，并发控制与重试
- `product-vector-search`: Product Entity/Repository + JdbcTemplate 向量写入与检索，含属性过滤与价格区间
- `faq-vector-search`: FaqEntry Entity/Repository + FaqVectorService + FaqController，相似度阈值 0.75 过滤
- `admin-reindex`: 全库批量向量化管道，并发分批调用 DashScope，进度反馈

### Modified Capabilities
（无已有规格需要修改）

## Impact

- **模块影响**：`voice-shopping-infrastructure`（Entity/Repository/VectorService）、`voice-shopping-ai`（EmbeddingService）、`voice-shopping-web`（Controller）
- **API 新增**：3 个 REST 端点（`/api/v1/admin/reindex`、`/api/v1/search`、`/api/v1/faq/ask`）
- **外部依赖**：DashScope text-embedding-v3 API（已有 SDK `dashscope-sdk-java:2.22.4`）
- **数据库**：依赖已有的 product/faq_entry 表结构及 HNSW 索引（Flyway 管理）
