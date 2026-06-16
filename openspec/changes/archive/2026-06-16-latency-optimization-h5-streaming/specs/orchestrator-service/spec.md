## MODIFIED Requirements

### Requirement: OrchestratorService 单一入口

系统 SHALL 在 `com.voiceshopping.business.orchestrator` 包下提供 `OrchestratorService`，对外暴露两个方法：

```java
EmotionResult handle(String sessionId, Long userId, String utterance);
Flux<StreamChunk> streamHandle(String sessionId, Long userId, String utterance);
```

`handle()` 方法保持原有行为不变（同步，用于 HTTP 调试接口等场景）。
`streamHandle()` 方法提供流式输出（详见 `stream-chunk-protocol` capability spec）。

#### Scenario: handle 仍可同步调用
- **WHEN** `handle("sess-1", 100L, "想买双跑鞋")` 被调用且 session 已存在
- **THEN** 方法返回非空 EmotionResult，行为与修改前完全一致

### Requirement: PRODUCT_RECOMMENDATION 分支链路

意图为 PRODUCT_RECOMMENDATION 时，系统 SHALL 按顺序执行：

1. 调用 `ClarifyService.decide`：若返回 ASK 则提前返回，若 READY 继续。
2. 调用 `ParallelRecommendService.recommend(sessionId, userId, utterance, mergedSlots)` 得到 RecommendResult。
3. 当 `voice-shopping.perspective.enabled = true` 且 RecommendResult.items 非空时，调用 `PerspectiveHubService.discuss`，将返回的多视角点评文本拼接到 utterance 末尾形成新 utterance。
4. **[CHANGED]** 调用 `EmotionService.wrap(sessionId, augmentedUtterance, userNeeds, recommendResult)` 得到 EmotionResult。userNeeds 由 mergedSlots 转换而来，格式为 `key1=val1,key2=val2,...`。
5. 用 RecommendResult 的 items 计算并写入 `last_recommendations` 字段。

#### Scenario: 完整推荐链路传 userNeeds
- **WHEN** 意图为 PRODUCT_RECOMMENDATION，mergedSlots = {category: "跑鞋", budget: 500}
- **THEN** EmotionService.wrap 接收 userNeeds = "category=跑鞋,budget=500"

### Requirement: PRODUCT_COMPARE 分支基于上一轮重排

意图为 PRODUCT_COMPARE 时，系统 SHALL 基于 `session_state.lastRecommendations` 改写 slots 后调用推荐和情感应答。

**[CHANGED]** `EmotionService.wrap` 调用 SHALL 传入 userNeeds 参数，由 compareSlots 转换而来。

#### Scenario: compare 分支传 userNeeds
- **WHEN** PRODUCT_COMPARE 分支，compareSlots = {category: "跑鞋", priceMin: 454.80, excludeProductIds: [...]}
- **THEN** EmotionService.wrap 接收由 compareSlots 转换的 userNeeds

### Requirement: CHITCHAT 分支闲聊兜底

意图为 CHITCHAT 时，系统 SHALL 调用 `EmotionService.wrap(sessionId, utterance, "", RecommendResult.EMPTY)`。userNeeds 传空字符串。

#### Scenario: 闲聊传空 userNeeds
- **WHEN** 意图为 CHITCHAT
- **THEN** EmotionService.wrap 的 userNeeds 参数为空字符串 ""

### Requirement: OrchestratorService 新增流式依赖注入

系统 SHALL 在 `OrchestratorService` 构造器追加注入：
- `EmotionStreamingService` —— 新增
- `TTSService` —— 新增（从 ai 模块引入）

依赖 final 字段、构造器注入，与现有依赖注入风格一致。

#### Scenario: 两个依赖完整注入
- **WHEN** Spring 容器启动
- **THEN** OrchestratorService bean 含 emotionStreamingService / ttsService 两个非 null 字段
