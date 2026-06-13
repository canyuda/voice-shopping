## Context

语音购物系统的 RecAgent 是主流程中负责商品推荐的核心智能体。当前已有：

- **ProductVectorService** — pgvector 余弦检索 + price/attributes 过滤（JdbcTemplate）
- **EmbeddingService** — DashScope text-embedding-v3 向量化
- **UserProfileService** — 静态+动态画像合并为 `UserProfileSnapshot`，Redis 缓存 24h
- **AgentFactory** — LRU 缓存的 Agent 工厂，已预留 `RecAgentBuilder`（目前 build 返回 null）
- **PromptLoader** — classpath `prompts/` 目录加载

缺失的完整推荐管线：向量检索 → SQL 过滤构建 → 画像加权精排 → LLM 理由生成 → 编排串联。

## Goals / Non-Goals

**Goals:**

- 实现端到端商品推荐管线：utterance + slots → Top 3 商品 + 推荐理由
- 复用现有 `ProductVectorService` 向量检索能力，通过通用 `extraFilter` 参数扩展过滤
- 用户画像参与排序：budget 锚点、品牌偏好、价格敏感、近期购买去重
- 空结果渐进兜底：放宽预算 → 去掉二级分类 → 返回空
- 提供 `POST /api/v1/agent/recommend` 调试接口

**Non-Goals:**

- 多租户（merchantId 过滤后续版本补充，本版本加 TODO 标记）
- UserProfileSnapshot 增加 recentBehavior 字段（近期购买去重降级为 brand+category 匹配）
- LLM Rerank（当前仅规则加权，不引入二次 LLM 排序）
- 个性化 embedding（不做 query 改写或用户偏好向量注入）

## Decisions

### D1: ProductVectorService.search() 改为通用过滤接口

**决策**：移除 `minPrice/maxPrice/attributeFilters/merchantId` 硬编码参数，改为 `String extraFilter, List<Object> extraParams` 两个参数接收外部拼装好的 WHERE 子句。

**理由**：
- 旧的 `search()` 将过滤逻辑硬编码在方法签名里，每增加一种过滤维度就要改签名
- `SqlFilterBuilder` 负责拼装 WHERE 片段，`search()` 只负责追加到 SQL 尾部
- 暂不传 merchantId，在 SQL 模板中加 `-- TODO: add merchant_id filter for multi-tenancy`

**替代方案**：新建一个 `searchWithFilter()` 方法保留旧方法。→ 拒绝，因为旧方法仅 SearchController 一处使用，直接改造更干净。

### D2: slots → SQL 过滤用 SqlFilterBuilder 而非硬编码

**决策**：独立 `SqlFilterBuilder` 工具类，提供 `fromSlots()`（通用）、`runningShoeFilter()`（跑鞋场景）、`merge()`（合并多 Filter）。

**理由**：
- 不同品类有不同的过滤维度，硬编码 if-else 不可扩展
- `Filter(clause, params)` 是纯数据 Record，便于测试和组合
- 跑鞋是首发场景，单独方法保证质量；后续品类以 fromSlots 为主

### D3: ProfileReranker 基于规则加权，不引入 LLM

**决策**：`computeScore()` 是纯 Java 函数，加权规则明确（budget 锚点 +0.25/-0.1、品牌偏好 ×0.2、价格敏感 -0.15、近期购买 -0.3），基于 `RecommendedItem.matchScore`（cosine similarity）做增量。

**理由**：
- 规则加权延迟 <1ms，LLM Rerank 延迟 500ms+，语音场景对延迟敏感
- 加权规则业务含义清晰，可调试、可测试
- 后续如需 LLM Rerank 可在 Top 20→Top 10 阶段插入，不影响当前架构

### D4: RecAgentBuilder 使用 mainChatModel (qwen-max)

**决策**：推荐理由生成需要理解商品属性与用户需求的匹配关系，选择 qwen-max。

**理由**：
- 推荐理由要求"指出商品最匹配用户需求的哪一点"，需要推理能力
- 3 条理由生成一次 API 调用，成本可控
- 轻量模型（qwen-turbo）在结构化推理任务上质量不稳定

### D5: buildQuery 仅取 slots 关键词拼接

**决策**：`buildQuery(utterance, slots)` 只提取 slots 中的属性值（category、brand、usage 等关键词），不拼接用户原话。

**理由**：
- embedding 模型对关键词密度敏感，用户原话中的冗余信息会稀释语义
- slots 已经是结构化的用户意图，直接用即可
- 后续如需 query 改写，可在此处插入 LLM

### D6: 空结果兜底复用 queryVector

**决策**：渐进放宽只改 SQL filter 参数，不重新调用 `EmbeddingService.embed()`。

**理由**：
- embedding API 调用有延迟（~200ms）和成本
- 向量不变，只是 SQL WHERE 条件放宽，复用 queryVector 是零成本的
- 最多 3 次 DB 查询（原始 → 预算+30% → 去掉二级分类），PG HNSW 检索 <50ms

### D7: 近期购买去重降级为 brand+category 匹配

**决策**：`recentBehavior` 中没有 productId，使用 brand+category 组合匹配判断"近期是否购买过同类商品"。

**理由**：
- UserProfileSnapshot 当前不含 recentBehavior，修改 Record 字段需同步改 merge() 和缓存
- recentBehavior 条目无 productId 字段，无法做精确去重
- brand+category 匹配是合理的近似，"买了 Asics 跑鞋" 和 "买了 Nike 跑鞋" 应区别对待
- 后续版本可添加 productId 到行为事件和 Snapshot

## Risks / Trade-offs

- **[Risk] 无 merchantId 过滤，数据隔离缺失** → 本版本为开发阶段，仅单商户数据。TODO 标记确保后续补上
- **[Risk] ProfileReranker 规则权重需调优** → 权重集中在一个 `computeScore` 方法里，便于 A/B 测试调整。单元测试覆盖边界场景
- **[Risk] LLM 推荐理由可能格式不合规** → `attachReasons` 用 try-catch 降级，返回空理由而非失败
- **[Risk] buildQuery 只用 slots 可能丢失信息** → slots 来源于上游 ClarifyAgent 提取，已经过意图理解，信息密度足够
