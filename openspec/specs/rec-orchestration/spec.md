# rec-orchestration

## Purpose

Recommendation pipeline orchestration capability: RecommendOrchestrator chains profile load → query build → embed → filter → candidate retrieval → fallback → rerank → top 3, plus RecommendDebugController for manual testing. ParallelRecommendService 提供基于 `CompletableFuture` 的并行等价实现。推荐主链路不再调用 RecommendReasonService，推荐理由由 EmotionAgent 合并生成。

## Requirements

### Requirement: RecommendOrchestrator 全流程编排
系统 SHALL 在 `com.voiceshopping.business.rec` 包下提供 `RecommendOrchestrator`，实现 `RecommendResult recommend(String sessionId, Long userId, String utterance, Map<String, Object> slots)` 方法，串联完整推荐流程。

流程步骤：
1. `profileService.load(userId)` 加载用户画像
2. `buildQuery(utterance, slots)` 拼接 embedding 查询文本
3. `embeddingService.embed(query)` 生成 queryVector
4. `SqlFilterBuilder` 构建 generic filter（含跑鞋场景合并）
5. **（本次修订新增）** 通过 `scopeCache.get(sessionId)` 取 `SessionScope`：缓存命中即用之；缓存 miss 则记 WARN 日志后兜底为 `SessionScope.platformWide(userId)`，**SHALL NOT 抛异常**
6. **（本次修订新增）** 通过 `scopeFilterBuilder.build(scope)` 生成 scope filter，再用 `sqlFilterBuilder.merge(genericFilter, scopeFilter)` 合流为最终 filter；scope 全平台时 scope filter 为 `Filter.EMPTY`，merge 后等价于 generic filter（保持向后兼容）
7. `candidatesService.fetchCandidates(queryVector, filter, 20)` 检索 Top 20
8. 空结果兜底（渐进放宽）—— 每次重试 SHALL 复用同一 scope filter，**绝不**为了凑结果而绕开 scope
9. `reranker.rerank(top20, profile, slots)` 画像加权
10. 取 Top 3
11. 不再调用 `reasonService.attachReasons`，直接返回 `new RecommendResult(top3, "professional")`，其中每个 item 的 reason 为 null

**实现约束**：
- 步骤 2-4、7-8 的实现 MAY 委托给协作类 `RecommendCandidateRetriever`；步骤 5-6 的 scope 解析与合流 SHALL 在编排层完成（即 `RecommendOrchestrator` / `ParallelRecommendService` 各自直接调 `scopeCache + scopeFilterBuilder + sqlFilterBuilder.merge`），retriever 内部 SHALL NOT 感知 sessionId / scope，保持职责单一。
- `RecommendOrchestrator.recommend` 的方法签名、返回值结构、异常语义、空结果行为 MUST NOT 因 scope 叠加而变化（除上述 WARN 日志外）。
- `RecommendOrchestrator` 构造器 SHALL 移除 `RecommendReasonService` 参数，不再注入该依赖。
- `RecommendReasonService` 类文件保留以备回滚，但主链路彻底不调用。

#### Scenario: 完整推荐流程返回 Top 3 且 reason 为 null（全平台 scope）
- **WHEN** sessionId="s1" 的 scope 为 `(7, [], null)`，调用 `recommend("s1", 7L, "我想买跑鞋", Map.of("category","跑鞋","budget",500))` 且有匹配商品
- **THEN** 返回 RecommendResult，items 包含 3 个商品，explanationTone 为 "professional"，每个 item 的 reason 字段为 null
- **THEN** `RecommendReasonService.attachReasons` 未被调用
- **THEN** 检索 SQL 不包含 `merchant_id` 子句

#### Scenario: 单商家 scope 过滤
- **WHEN** sessionId="s2" 的 scope 为 `(7, [5], 88)`（PRODUCT_PAGE），调用 recommend
- **THEN** 检索 SQL 包含 `merchant_id IN (?)`，参数为 5
- **THEN** 渐进 fallback 的每一次重试 SQL 同样包含 `merchant_id IN (?)`

#### Scenario: scope 缓存 miss 走全平台兜底
- **WHEN** sessionId="s-old" 的 scope 在 Redis 已过期
- **THEN** 不抛异常，返回正常 RecommendResult
- **THEN** 日志中存在 WARN "Scope cache miss for sessionId=s-old, falling back to platform-wide"
- **THEN** 检索 SQL 不包含 `merchant_id` 子句

#### Scenario: 无匹配商品返回空结果
- **WHEN** 调用 `recommend(...)` 且所有放宽策略后仍无匹配
- **THEN** 返回 `RecommendResult(List.of(), "empty")`

### Requirement: 空结果渐进兜底
`RecommendOrchestrator` 与 `ParallelRecommendService` SHALL 在 Top 20 为空时逐步放宽过滤条件重新检索，复用 queryVector 不重新 embedding。

放宽顺序：
1. 预算 +30%（如 budget=500 → 放宽到 650）
2. 去掉二级分类（category_l2 过滤）
3. 返回空 RecommendResult

**实现约束（本次修订新增）**：
- 两个 service MUST 复用同一份兜底实现（推荐通过协作类 `RecommendCandidateRetriever` 集中实现），不得各写一套。
- 兜底语义（顺序、预算 +30% 比例、去 categoryL2 的字段名）MUST 与原实现完全一致。

#### Scenario: 预算放宽后有结果
- **WHEN** 初始检索返回空，预算 +30% 后有匹配
- **THEN** 使用放宽后的结果继续后续流程

#### Scenario: 全部放宽后仍无结果
- **WHEN** 初始检索返回空，预算放宽后仍空，去掉二级分类后仍空
- **THEN** 返回 `RecommendResult(List.of(), "empty")`

#### Scenario: ParallelRecommendService 兜底等价
- **WHEN** 同一组无匹配的 (utterance, slots) 同时传入 `RecommendOrchestrator` 与 `ParallelRecommendService`
- **THEN** 两者均触发完整三级 fallback 后返回 `RecommendResult.EMPTY`

### Requirement: RecommendDebugController 调试接口
系统 SHALL 提供两个调试接口对照同一推荐流程的串行与并行实现，便于 D6 重构后等价性的人工验证：

- `POST /api/v1/agent/recommend`：调 `RecommendOrchestrator.recommend`（串行基线）。
- `POST /api/v1/agent/recommend/parallel`：调 `ParallelRecommendService.recommend`（`CompletableFuture` 并行实现）。

两个接口 MUST 共用同一份请求体（`RecommendDebugReq`，含 `utterance @NotBlank` 与 `slots Map<String,Object>`）以及同一对 `sessionId`、`userId` query 参数；返回类型 MUST 同为 `ApiResult<RecommendResult>`。

#### Scenario: 串行接口正常调用
- **WHEN** POST `/api/v1/agent/recommend?sessionId=test&userId=1` 传入 `{"utterance":"推荐跑鞋","slots":{"category":"跑鞋","budget":500}}`
- **THEN** 返回 `ApiResult<RecommendResult>`，code 为 200

#### Scenario: 并行接口正常调用
- **WHEN** POST `/api/v1/agent/recommend/parallel?sessionId=test&userId=1` 传入相同请求体
- **THEN** 返回 `ApiResult<RecommendResult>`，code 为 200，且 `data.items` 的 productId 列表（含顺序）与串行接口同输入下一致、`data.explanationTone` 相同

#### Scenario: 参数校验失败
- **WHEN** 任一接口传入空 utterance
- **THEN** 返回 400 错误

### Requirement: ParallelRecommendService 并行实现
系统 SHALL 在 `com.voiceshopping.business.rec` 包下提供 `ParallelRecommendService`，提供与 `RecommendOrchestrator.recommend` **结果等价**的实现，但用 `CompletableFuture` 并行执行 profile 加载与候选召回链。

约束：
1. 方法签名 MUST 与 `RecommendOrchestrator.recommend` 完全一致：`RecommendResult recommend(String sessionId, Long userId, String utterance, Map<String, Object> slots)`。
2. MUST 使用 `CompletableFuture.supplyAsync` 并行启动两条腿：
   - **Profile 腿**：`userId != null ? profileService.load(userId) : null`，仅依赖 `userId`。
   - **Candidates 腿**：`buildQuery → embeddingService.embed → buildFilter`（generic filter）。
3. 候选召回 MUST 复用与 `RecommendOrchestrator` **同一份** fallback 实现（即 `RecommendCandidateRetriever.retrieve`），保证两级 fallback 语义（预算 +30% / 去 categoryL2）一致。
4. **（本次修订新增）** scope 解析与合流 SHALL 与 `RecommendOrchestrator` 的步骤 5-6 完全一致：`scopeCache.get(sessionId)` → 缓存 miss 兜底 `platformWide(userId)` + WARN 日志 → `scopeFilterBuilder.build` → `sqlFilterBuilder.merge`。scope 解析 MAY 与 profile 加载并行（同一条 future 链或独立 future），但合流 MUST 在 candidates 腿的 `retrieve` 调用之前完成。
5. **（本次修订新增）** `ParallelRecommendService` 与 `RecommendOrchestrator` 在同一组 (sessionId, userId, utterance, slots) 输入下，最终传入 `productVectorService.search` 的 filter clause + params 必须等价（含 scope 子句），fallback 路径同样等价。
6. `ParallelRecommendService` 构造器 SHALL 移除 `RecommendReasonService` 参数，不再注入该依赖。`recommend()` 方法 SHALL 不调用 `reasonService.attachReasons`，直接返回 reason=null 的 topK。

#### Scenario: 并行实现 scope 等价
- **WHEN** scope=`(7,[5],null)`，同一组 (sessionId, userId, utterance, slots) 同时传入 `RecommendOrchestrator` 与 `ParallelRecommendService`
- **THEN** 两者最终传给 `productVectorService.search` 的 filter clause 与 params 等价（含 `merchant_id IN (?)`）

#### Scenario: 并行实现 scope miss 兜底
- **WHEN** scope cache 中无 sessionId 记录
- **THEN** 两个服务都记 WARN 日志并按全平台 scope 继续执行

#### Scenario: 并行实现不调用 reasonService
- **WHEN** 同一组 (sessionId, userId, utterance, slots) 传入 ParallelRecommendService.recommend
- **THEN** `RecommendReasonService.attachReasons` 未被调用
- **THEN** 返回的 RecommendResult.items 中每个 item 的 reason 为 null

#### Scenario: 与串行版本结果等价（不含 reason）
- **WHEN** 同一组 (sessionId, userId, utterance, slots) 同时传给 `RecommendOrchestrator.recommend` 与 `ParallelRecommendService.recommend`
- **THEN** 两者返回的 `RecommendResult.items()` 中商品 productId 集合相同、顺序相同、`explanationTone` 相同（reason 均为 null）

#### Scenario: profile 腿失败 fail-fast
- **WHEN** `profileService.load` 抛 RuntimeException
- **THEN** `ParallelRecommendService.recommend` 抛 `IllegalStateException`（不静默吞掉异常，符合全局 fail-fast 规范）

#### Scenario: 全部 fallback 失败返回空结果
- **WHEN** 初始召回、预算放宽、去 categoryL2 三次均返回空
- **THEN** 方法返回 `RecommendResult.EMPTY`，与 `RecommendOrchestrator` 行为一致

#### Scenario: userId 为 null 不加载 profile
- **WHEN** 传入 `userId=null`
- **THEN** profile 腿直接返回 null 而非调用 `profileService.load`，候选腿正常并行执行

### Requirement: priceMin slot 过滤

`RecommendCandidateRetriever.buildFilter(slots)` SHALL 识别 slots 中的 `priceMin`（数值类型，BigDecimal / Number）字段，并将其转换为 SQL 过滤条件 `price >= :priceMin`。`SqlFilterBuilder` MUST 增加对应分支生成该 SQL 片段。

`priceMin` 与 `budget` 互不替代：当二者同时存在时，候选商品 MUST 同时满足 `price >= priceMin AND price <= budget`。

#### Scenario: 单独提供 priceMin

- **WHEN** slots = `{category: "跑鞋", priceMin: 500}`
- **THEN** 检索 SQL WHERE 子句包含 `price >= 500`，结果 MUST NOT 包含 price < 500 的商品

#### Scenario: priceMin 与 budget 共存

- **WHEN** slots = `{category: "跑鞋", priceMin: 500, budget: 1000}`
- **THEN** 检索结果 MUST 满足 `500 <= price <= 1000`

#### Scenario: priceMin 缺失时不影响

- **WHEN** slots = `{category: "跑鞋", budget: 500}`
- **THEN** 检索行为与新增本字段前一致

### Requirement: excludeProductIds slot 过滤

`RecommendCandidateRetriever.buildFilter(slots)` SHALL 识别 slots 中的 `excludeProductIds`（List<Long> 或 List<Number>）字段，并转换为 SQL 过滤条件 `id NOT IN (:excludeProductIds)`。`SqlFilterBuilder` MUST 增加对应分支。

空列表 MUST 等价于不施加该过滤；非空列表 MUST 排除其中所有商品。

#### Scenario: 排除上轮已推商品

- **WHEN** slots.excludeProductIds = [8821, 8822, 8823]
- **THEN** 检索结果 MUST NOT 包含这三个商品

#### Scenario: 空列表不影响

- **WHEN** slots.excludeProductIds = []
- **THEN** 检索行为与不传该字段一致
