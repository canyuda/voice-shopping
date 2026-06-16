## MODIFIED Requirements

### Requirement: RecommendOrchestrator 全流程编排

系统 SHALL 在 `com.voiceshopping.business.rec` 包下提供 `RecommendOrchestrator`，实现 `RecommendResult recommend(String sessionId, Long userId, String utterance, Map<String, Object> slots)` 方法，串联推荐流程。

流程步骤（修改部分标 **[CHANGED]**）：
1. `profileService.load(userId)` 加载用户画像
2. `buildQuery(utterance, slots)` 拼接 embedding 查询文本
3. `embeddingService.embed(query)` 生成 queryVector
4. `SqlFilterBuilder` 构建 generic filter（含跑鞋场景合并）
5. 通过 `scopeCache.get(sessionId)` 取 `SessionScope`：缓存命中即用之；缓存 miss 则记 WARN 日志后兜底为 `SessionScope.platformWide(userId)`
6. 通过 `scopeFilterBuilder.build(scope)` 生成 scope filter，再用 `sqlFilterBuilder.merge(genericFilter, scopeFilter)` 合流为最终 filter
7. `candidatesService.fetchCandidates(queryVector, filter, 20)` 检索 Top 20
8. 空结果兜底（渐进放宽）
9. `reranker.rerank(top20, profile, slots)` 画像加权
10. 取 Top 3
11. **[CHANGED]** 不再调用 `reasonService.attachReasons`，直接返回 `new RecommendResult(top3, "professional")`，其中每个 item 的 reason 为 null

**实现约束**：
- `RecommendOrchestrator` 构造器 SHALL 移除 `RecommendReasonService` 参数，不再注入该依赖。
- 类文件保留以备回滚，但主链路彻底不调用。

#### Scenario: 完整推荐流程返回 Top 3 且 reason 为 null
- **WHEN** 调用 `recommend("s1", 7L, "我想买跑鞋", Map.of("category","跑鞋","budget",500))` 且有匹配商品
- **THEN** 返回 RecommendResult，items 包含 3 个商品，explanationTone 为 "professional"，每个 item 的 reason 字段为 null
- **THEN** `RecommendReasonService.attachReasons` 未被调用

#### Scenario: 无匹配商品返回空结果
- **WHEN** 调用 `recommend(...)` 且所有放宽策略后仍无匹配
- **THEN** 返回 `RecommendResult(List.of(), "empty")`

### Requirement: ParallelRecommendService 并行实现

系统 SHALL 在 `com.voiceshopping.business.rec` 包下提供 `ParallelRecommendService`，与 `RecommendOrchestrator.recommend` 结果等价。

**[CHANGED]** `ParallelRecommendService` 构造器 SHALL 移除 `RecommendReasonService` 参数，不再注入该依赖。`recommend()` 方法 SHALL 不调用 `reasonService.attachReasons`，直接返回 reason=null 的 topK。

其余所有约束（方法签名、并行执行、scope 等价、fallback 语义）保持不变。

#### Scenario: 并行实现不调用 reasonService
- **WHEN** 同一组 (sessionId, userId, utterance, slots) 传入 ParallelRecommendService.recommend
- **THEN** `RecommendReasonService.attachReasons` 未被调用
- **THEN** 返回的 RecommendResult.items 中每个 item 的 reason 为 null

#### Scenario: 与串行版本结果等价（不含 reason）
- **WHEN** 同一组 (sessionId, userId, utterance, slots) 同时传给 `RecommendOrchestrator.recommend` 与 `ParallelRecommendService.recommend`
- **THEN** 两者返回的 `RecommendResult.items()` 中商品 productId 集合相同、顺序相同、`explanationTone` 相同（reason 均为 null）
