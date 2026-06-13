## Why

RecAgent 是语音购物主流程中核心的商品推荐智能体，负责从海量商品池中检索候选、结合用户画像加权精排、生成自然推荐理由。当前已有向量检索（ProductVectorService）、用户画像加载（UserProfileService）、Agent 工厂骨架，但推荐链路尚未串联。本变更实现完整的 RecAgent 推荐管线，支撑"语音说出需求 → 返回 Top 3 商品 + 推荐理由"的核心购物体验。

## What Changes

- 新增 `RecommendedItem` / `RecommendResult` / `Filter` DTO（`common/dto/agent`）
- 新增 `SqlFilterBuilder`（`infrastructure/vector`），从 slots 组装 SQL WHERE 片段，含跑鞋场景专用过滤和多过滤器合并
- **BREAKING** 改造 `ProductVectorService.search()`：移除 merchantId / minPrice / maxPrice / attributeFilters 参数，改为接受 `extraFilter` + `extraParams`；暂不支持多租户（加 TODO 标记）
- 同步改造 `SearchController`（`/api/v1/search`），使用 SqlFilterBuilder + 新 search 签名
- 新增 `RecommendCandidatesService`（`business/rec`），封装向量检索 + 过滤逻辑
- 新增 `ProfileReranker`（`business/rec`），基于用户画像加权重排候选集
- 实现 `RecAgentBuilder`，使用 mainChatModel（qwen-max），加载 `prompts/rec.txt`
- 新增 `prompts/rec.txt` 推荐理由生成 Prompt
- 新增 `RecommendReasonService`（`business/rec`），LLM 批量生成推荐理由
- 新增 `RecommendOrchestrator`（`business/rec`），串联全流程
- 新增 `RecommendDebugController`（`POST /api/v1/agent/recommend`）
- 空结果兜底：渐进放宽过滤条件（预算 +30% → 去掉二级分类），复用 queryVector

## Capabilities

### New Capabilities

- `rec-candidate-retrieval`: SQL 过滤构建（SqlFilterBuilder）+ pgvector 向量检索候选集（RecommendCandidatesService）
- `profile-reranking`: 用户画像加权精排（ProfileReranker），含 budget 锚点、品牌偏好、价格敏感、近期购买去重
- `rec-reason-generation`: LLM 推荐理由生成（RecAgentBuilder + RecommendReasonService + prompt）
- `rec-orchestration`: 推荐全流程编排（RecommendOrchestrator）+ 空结果兜底 + 调试接口

### Modified Capabilities

- `product-vector-search`: search 方法签名变更，移除旧过滤参数，新增 extraFilter/extraParams
- `agent-builder-skeleton`: RecAgentBuilder 从 TODO 状态实现为完整的 Builder

## Impact

- **代码模块**：common（DTO）、infrastructure（ProductVectorService、SqlFilterBuilder）、business（rec 包）、ai（RecAgentBuilder）、web（SearchController、RecommendDebugController）
- **API 变更**：`GET /api/v1/search` 入参简化（移除 minPrice/maxPrice/attributes，改为通用 filter JSON）；新增 `POST /api/v1/agent/recommend`
- **依赖**：无新外部依赖，复用现有 EmbeddingService / DashScopeChatModel / JdbcTemplate
- **数据**：无数据库 schema 变更
