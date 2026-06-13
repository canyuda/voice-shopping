## ADDED Requirements

### Requirement: ParallelRecommendService 并行实现
系统 SHALL 在 `com.voiceshopping.business.rec` 包下提供 `ParallelRecommendService`，提供与 `RecommendOrchestrator.recommend` **结果等价**的实现，但用 `CompletableFuture` 并行执行 profile 加载与候选召回链。

约束：
1. 方法签名 MUST 与 `RecommendOrchestrator.recommend` 完全一致：`RecommendResult recommend(String sessionId, Long userId, String utterance, Map<String, Object> slots)`。
2. MUST 使用 `CompletableFuture.supplyAsync` 并行启动两条腿：
   - **Profile 腿**：`userId != null ? profileService.load(userId) : null`，仅依赖 `userId`。
   - **Candidates 腿**：`buildQuery → embeddingService.embed → buildFilter → retrieveWithFallback`，仅依赖 `utterance + slots`。
3. 候选召回 MUST 复用与 `RecommendOrchestrator` **同一份** fallback 实现（即 `RecommendCandidateRetriever.retrieve`），保证两级 fallback 语义（预算 +30% / 去 categoryL2）一致。
4. MUST 用 `thenCombine` 合流后串行执行：rerank → 取 Top 3 → `attachReasons`。
5. 候选为空时 MUST 返回 `RecommendResult.EMPTY`，与 `RecommendOrchestrator` 一致。
6. 任一腿抛出异常 MUST fail-fast 向上抛出 `IllegalStateException`（包装原异常）。
7. `RecommendOrchestrator` 类的**外部行为** MUST NOT 改变；本能力可借助提取协作类（如 `RecommendCandidateRetriever`）让两者复用 fallback，但 `RecommendOrchestrator.recommend` 的方法签名、返回值、异常语义、空结果行为 MUST 保持完全等价。

#### Scenario: 与串行版本结果等价
- **WHEN** 同一组 (sessionId, userId, utterance, slots) 同时传给 `RecommendOrchestrator.recommend` 与 `ParallelRecommendService.recommend`
- **THEN** 两者返回的 `RecommendResult.items()` 中商品 productId 集合相同、顺序相同、`explanationTone` 相同（reasons 文本因 LLM 不确定可能不同，不强求逐字相等）

#### Scenario: profile 腿失败 fail-fast
- **WHEN** `profileService.load` 抛 RuntimeException
- **THEN** `ParallelRecommendService.recommend` 抛 `IllegalStateException`（不静默吞掉异常，符合全局 fail-fast 规范）

#### Scenario: 全部 fallback 失败返回空结果
- **WHEN** 初始召回、预算放宽、去 categoryL2 三次均返回空
- **THEN** 方法返回 `RecommendResult.EMPTY`，与 `RecommendOrchestrator` 行为一致

#### Scenario: userId 为 null 不加载 profile
- **WHEN** 传入 `userId=null`
- **THEN** profile 腿直接返回 null 而非调用 `profileService.load`，候选腿正常并行执行

## MODIFIED Requirements

### Requirement: RecommendOrchestrator 全流程编排
系统 SHALL 在 `com.voiceshopping.business.rec` 包下提供 `RecommendOrchestrator`，实现 `RecommendResult recommend(String sessionId, Long userId, String utterance, Map<String, Object> slots)` 方法，串联完整推荐流程。

流程步骤：
1. `profileService.load(userId)` 加载用户画像
2. `buildQuery(utterance, slots)` 拼接 embedding 查询文本
3. `embeddingService.embed(query)` 生成 queryVector
4. `SqlFilterBuilder` 构建 Filter（含跑鞋场景合并）
5. `candidatesService.fetchCandidates(queryVector, filter, 20)` 检索 Top 20
6. 空结果兜底（渐进放宽）
7. `reranker.rerank(top20, profile, slots)` 画像加权
8. 取 Top 3
9. `reasonService.attachReasons(sessionId, userNeeds, top3)` 生成理由
10. 返回 `RecommendResult(top3, "professional")`

**实现约束（本次修订新增）**：
- 步骤 2-6（buildQuery / buildFilter / retrieveWithFallback）的实现 MAY 委托给协作类 `RecommendCandidateRetriever`，使 `ParallelRecommendService` 与 `RecommendOrchestrator` 复用同一份 fallback 语义。
- 此委托是**纯重构**，`RecommendOrchestrator.recommend` 的方法签名、返回值结构、异常语义、空结果行为 MUST NOT 发生变化。

#### Scenario: 完整推荐流程返回 Top 3
- **WHEN** 调用 `recommend("sess1", 1L, "我想买跑鞋", Map.of("category","跑鞋","budget",500))` 且有匹配商品
- **THEN** 返回 RecommendResult，items 包含 3 个带推荐理由的商品，explanationTone 为 "professional"

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
