# rec-orchestration

## Purpose

Recommendation pipeline orchestration capability: RecommendOrchestrator chains profile load → query build → embed → filter → candidate retrieval → fallback → rerank → top 3 → reason generation, plus RecommendDebugController for manual testing.

## Requirements

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

#### Scenario: 完整推荐流程返回 Top 3
- **WHEN** 调用 `recommend("sess1", 1L, "我想买跑鞋", Map.of("category","跑鞋","budget",500))` 且有匹配商品
- **THEN** 返回 RecommendResult，items 包含 3 个带推荐理由的商品，explanationTone 为 "professional"

#### Scenario: 无匹配商品返回空结果
- **WHEN** 调用 `recommend(...)` 且所有放宽策略后仍无匹配
- **THEN** 返回 `RecommendResult(List.of(), "empty")`

### Requirement: 空结果渐进兜底
`RecommendOrchestrator` SHALL 在 Top 20 为空时逐步放宽过滤条件重新检索，复用 queryVector 不重新 embedding。

放宽顺序：
1. 预算 +30%（如 budget=500 → 放宽到 650）
2. 去掉二级分类（category_l2 过滤）
3. 返回空 RecommendResult

#### Scenario: 预算放宽后有结果
- **WHEN** 初始检索返回空，预算 +30% 后有匹配
- **THEN** 使用放宽后的结果继续后续流程

#### Scenario: 全部放宽后仍无结果
- **WHEN** 初始检索返回空，预算放宽后仍空，去掉二级分类后仍空
- **THEN** 返回 `RecommendResult(List.of(), "empty")`

### Requirement: RecommendDebugController 调试接口
系统 SHALL 提供 `POST /api/v1/agent/recommend` 接口，接受 `sessionId`、`userId` 和 `RecommendDebugReq`（含 utterance 和 slots），调用 RecommendOrchestrator 返回 `ApiResult<RecommendResult>`。

#### Scenario: 调试接口正常调用
- **WHEN** POST `/api/v1/agent/recommend` 传入 `{"sessionId":"test","userId":1,"utterance":"推荐跑鞋","slots":{"category":"跑鞋","budget":500}}`
- **THEN** 返回 `ApiResult<RecommendResult>`，code 为 200

#### Scenario: 参数校验失败
- **WHEN** POST `/api/v1/agent/recommend` 传入空 utterance
- **THEN** 返回 400 错误
