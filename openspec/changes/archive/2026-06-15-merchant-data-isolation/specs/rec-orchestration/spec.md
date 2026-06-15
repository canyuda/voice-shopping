## MODIFIED Requirements

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
11. `reasonService.attachReasons(sessionId, userNeeds, top3)` 生成理由
12. 返回 `RecommendResult(top3, "professional")`

**实现约束**：
- 步骤 2-4、7-8 的实现 MAY 委托给协作类 `RecommendCandidateRetriever`；步骤 5-6 的 scope 解析与合流 SHALL 在编排层完成（即 `RecommendOrchestrator` / `ParallelRecommendService` 各自直接调 `scopeCache + scopeFilterBuilder + sqlFilterBuilder.merge`），retriever 内部 SHALL NOT 感知 sessionId / scope，保持职责单一。
- `RecommendOrchestrator.recommend` 的方法签名、返回值结构、异常语义、空结果行为 MUST NOT 因 scope 叠加而变化（除上述 WARN 日志外）。

#### Scenario: 完整推荐流程返回 Top 3（全平台 scope）
- **WHEN** sessionId="s1" 的 scope 为 `(7, [], null)`，调用 `recommend("s1", 7L, "我想买跑鞋", Map.of("category","跑鞋","budget",500))` 且有匹配商品
- **THEN** 返回 RecommendResult，items 包含 3 个带推荐理由的商品，explanationTone 为 "professional"
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

#### Scenario: 并行实现 scope 等价
- **WHEN** scope=`(7,[5],null)`，同一组 (sessionId, userId, utterance, slots) 同时传入 `RecommendOrchestrator` 与 `ParallelRecommendService`
- **THEN** 两者最终传给 `productVectorService.search` 的 filter clause 与 params 等价（含 `merchant_id IN (?)`）

#### Scenario: 并行实现 scope miss 兜底
- **WHEN** scope cache 中无 sessionId 记录
- **THEN** 两个服务都记 WARN 日志并按全平台 scope 继续执行
