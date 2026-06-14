## ADDED Requirements

### Requirement: OrchestratorService 单一入口

系统 SHALL 在 `com.voiceshopping.business.orchestrator` 包下提供 `OrchestratorService`，对外只暴露一个方法：

```java
EmotionResult handle(String sessionId, Long userId, String utterance);
```

该方法 MUST 串联意图理解、意图兜底矫正、按意图分支执行、合规兜底、记忆/状态写回，并返回最终 EmotionResult。

#### Scenario: 接收非空入参完成一轮对话

- **WHEN** `handle("sess-1", 100L, "想买双跑鞋")` 被调用且 session 已存在
- **THEN** 方法返回非空 EmotionResult，session_state 已被更新到 PG/Redis，短期记忆中已写入对应的 USER 与 ASSISTANT turn

### Requirement: handle 全流程 Timer 监控

系统 SHALL 使用 `MeterRegistry` 监控 `handle` 方法全流程耗时，Timer 名为 `voice.shopping.orchestrator.handle`，tag 仅包含 `intent`（最终矫正后的意图枚举名）。Timer MUST 在方法入口 start，finally 块 stop。`session.id` MUST NOT 作为 tag 暴露（高基数）。

#### Scenario: 任意分支退出时间均被记录

- **WHEN** handle 因任何原因退出（正常返回或抛异常）
- **THEN** Timer sample 必须 stop，并以最终意图作为 tag 写入 MeterRegistry

### Requirement: handle 不创建 session

系统 SHALL 在 handle 内部仅查找 session，不调用 `SessionService.getOrCreate`。当 sessionId 对应的 session 不存在时，handle MUST 抛 `NotFoundException`，由全局异常处理器返回 404。

#### Scenario: session 不存在抛 NotFoundException

- **WHEN** `handle("不存在的 sess", 100L, "...")`
- **THEN** 抛出 `NotFoundException`，不写入任何 session_state 或短期记忆

### Requirement: 异步触发画像预热事件

系统 SHALL 在 handle 内部通过 `VoiceEventPublisher.publish(new UserSpokenEvent(...))` 异步触发画像预热与行为回流事件，事件发布 MUST 在意图理解之前完成，且不阻塞主链路（Spring `@Async` 监听）。

#### Scenario: 每次 handle 必发布 UserSpokenEvent

- **WHEN** handle 进入意图理解之前
- **THEN** 一个 UserSpokenEvent 已被发布到应用事件总线，包含 sessionId / userId / utterance / timestamp

### Requirement: 短期记忆写入用户与助手 Turn

系统 SHALL 在 handle 中向 `ShortTermMemory` 写入两条 turn：

1. 在意图理解之前写入 USER turn（content = utterance）
2. 在 EmotionResult 计算完成后、方法返回之前写入 ASSISTANT turn（content = emotionResult.speechText）

#### Scenario: 记忆顺序为先 USER 后 ASSISTANT

- **WHEN** handle 完成
- **THEN** 短期记忆中本轮新增的两条 turn 按时间顺序为先 USER 后 ASSISTANT

### Requirement: 意图兜底矫正规则①——priceDirection 锚定

当 IntentService 输出的意图为 `PRODUCT_RECOMMENDATION` 或 `CLARIFY_NEEDED`、且 `session_state.lastRecommendations` 非空、且当前 slots 中 `priceDirection` 取值为 `"cheaper"` 或 `"expensive"` 时，系统 SHALL 强制将意图改写为 `PRODUCT_COMPARE`。

其他 priceDirection 取值（包括 null）MUST NOT 触发本规则。本规则 MUST 优先于规则②执行。

#### Scenario: 推荐意图 + 上轮有推荐 + cheaper → 强制 COMPARE

- **WHEN** IntentService 给 `PRODUCT_RECOMMENDATION`，session_state.lastRecommendations 含 3 件商品，slots.priceDirection = "cheaper"
- **THEN** 矫正后意图为 `PRODUCT_COMPARE`

#### Scenario: 上轮无推荐 → 不矫正

- **WHEN** session_state.lastRecommendations 为空，slots.priceDirection = "cheaper"
- **THEN** 意图保持 LLM 给出的原值

#### Scenario: priceDirection 是未知值 → 不矫正

- **WHEN** slots.priceDirection = "lower"（不在白名单内）
- **THEN** 意图保持 LLM 给出的原值

### Requirement: 意图兜底矫正规则②——信息已足

当 IntentService 输出的意图为 `CLARIFY_NEEDED`、且规则①未触发时，系统 SHALL 合并 `session_state.slots` 与当前 slots，调用 `ClarifyRuleService.missingSlots(category, mergedSlots)`，若返回空列表则强制将意图改写为 `PRODUCT_RECOMMENDATION`。

合并规则：当前 slots 中 value 非空的字段覆盖 state.slots 同名字段。

本规则 MUST NOT 对其他意图（PRODUCT_RECOMMENDATION / PRODUCT_COMPARE / CHITCHAT / ORDER_CONFIRM / OUT_OF_SCOPE）生效。

#### Scenario: CLARIFY 但合并后槽位完整 → 改写 RECOMMENDATION

- **WHEN** IntentService 给 CLARIFY_NEEDED，state.slots 含 `{category: "跑鞋", scenario: "塑胶跑道"}`，当前 slots 含 `{budget: 500}`，按品类规则需要 category + scenario + budget
- **THEN** 矫正后意图为 PRODUCT_RECOMMENDATION

#### Scenario: PRODUCT_RECOMMENDATION 但槽位实际不全 → 不矫正

- **WHEN** IntentService 给 PRODUCT_RECOMMENDATION，槽位实际不足
- **THEN** 意图保持 PRODUCT_RECOMMENDATION（由后续 ClarifyService 内部决定 ASK / READY）

### Requirement: PRODUCT_RECOMMENDATION 分支链路

意图为 PRODUCT_RECOMMENDATION 时，系统 SHALL 按顺序执行：

1. 调用 `ClarifyService.decide`：若返回 ASK 则提前返回（同 CLARIFY 分支处理），若 READY 继续。
2. 调用 `ParallelRecommendService.recommend(sessionId, userId, utterance, mergedSlots)` 得到 RecommendResult。
3. 当 `voice-shopping.perspective.enabled = true` 且 RecommendResult.items 非空时，调用 `PerspectiveHubService.discuss`，将返回的多视角点评文本拼接到 utterance 末尾形成新 utterance。
4. 调用 `EmotionService.wrap(sessionId, augmentedUtterance, recommendResult)` 得到 EmotionResult。
5. 用 RecommendResult 的 items 计算并写入 `last_recommendations` 字段（见对应 requirement）。

#### Scenario: 完整推荐链路

- **WHEN** 意图为 PRODUCT_RECOMMENDATION，槽位充足，RecommendResult 含 3 件商品
- **THEN** 返回的 EmotionResult 由 EmotionService 基于 3 件商品生成，session_state.lastRecommendations 已被更新

#### Scenario: 推荐结果为空仍走情感应答

- **WHEN** ParallelRecommendService 返回 RecommendResult.EMPTY
- **THEN** 仍调用 EmotionService.wrap，且不调用 PerspectiveHubService（即使开关开启）

### Requirement: CLARIFY_NEEDED 分支链路

意图为 CLARIFY_NEEDED 时，系统 SHALL 调用 `ClarifyService.decide(sessionId, utterance, mergedSlots)`：

- 若 action = ASK：直接构造 `EmotionResult(question, List.of())` 返回，session_state.pendingAsk 写入 question，phase 写入 `CLARIFY`。
- 若 action = READY：退化执行 PRODUCT_RECOMMENDATION 完整链路（含 perspective）。

#### Scenario: ASK 不进入推荐

- **WHEN** ClarifyService 返回 ASK
- **THEN** 跳过 ParallelRecommendService 和 EmotionService，pendingAsk 已写入

#### Scenario: READY 走完整推荐

- **WHEN** ClarifyService 返回 READY
- **THEN** ParallelRecommendService 与 EmotionService 均被调用，pendingAsk 清空为 null

### Requirement: PRODUCT_COMPARE 分支基于上一轮重排

意图为 PRODUCT_COMPARE 时，系统 SHALL 基于 `session_state.lastRecommendations`（反序列化为 `LastRecommendationsSnapshot`）改写 slots：

- 当 priceDirection = "cheaper"：写入 `budget = maxPrice * 0.8`（覆盖原 budget）。
- 当 priceDirection = "expensive"：写入 `priceMin = minPrice * 1.2`（不修改 budget）。
- 总是写入 `excludeProductIds = [上轮所有 productId]`。

随后调用 `ParallelRecommendService.recommend` → `EmotionService.wrap`。

若 lastRecommendations 缺失或反序列化失败，分支 MUST 退化为 PRODUCT_RECOMMENDATION 完整链路（含 ClarifyService.decide）。

#### Scenario: cheaper 路径用 maxPrice * 0.8 作为新 budget

- **WHEN** lastRecommendations.maxPrice = 599、priceDirection = "cheaper"
- **THEN** 传给 ParallelRecommendService 的 slots.budget = 479.20，excludeProductIds 包含上轮所有 productId

#### Scenario: expensive 路径用 minPrice * 1.2 作为 priceMin 且不污染 budget

- **WHEN** lastRecommendations.minPrice = 379、priceDirection = "expensive"，原 slots.budget = 1000
- **THEN** 传给 ParallelRecommendService 的 slots.priceMin = 454.80，slots.budget 仍为 1000

#### Scenario: 上轮 last_recommendations 缺失退化为推荐

- **WHEN** PRODUCT_COMPARE 但 session_state.lastRecommendations = null
- **THEN** 分支退化执行 PRODUCT_RECOMMENDATION 完整链路

### Requirement: ORDER_CONFIRM 分支占位

意图为 ORDER_CONFIRM 时，系统 SHALL 直接返回 `new EmotionResult("好，给你下单（完整下单逻辑后续补）", List.of())`，不执行任何下单业务逻辑，但仍按规范写入短期记忆与 session_state。

#### Scenario: 返回固定话术

- **WHEN** 意图为 ORDER_CONFIRM
- **THEN** speechText 等于 "好，给你下单（完整下单逻辑后续补）"，displayBlocks 为空列表

### Requirement: CHITCHAT 分支闲聊兜底

意图为 CHITCHAT 时，系统 SHALL 调用 `EmotionService.wrap(sessionId, utterance, RecommendResult.EMPTY)`。若 EmotionService 返回的 speechText 为推荐式空兜底（"这个条件下合适的不多，要不要放宽点预算再看看？"）时，系统 SHALL 用闲聊兜底文案替换："不太懂这个，我们聊点你想买啥呗？"。

#### Scenario: 闲聊调用 EmotionService 不带商品

- **WHEN** 意图为 CHITCHAT
- **THEN** 调用 EmotionService.wrap 时第三个参数是 RecommendResult.EMPTY

### Requirement: OUT_OF_SCOPE 分支固定话术

意图为 OUT_OF_SCOPE 时，系统 SHALL 直接返回 `new EmotionResult("只负责帮你挑商品，这个问题回头可以找客服处理哈。我们继续聊想买什么？", List.of())`。

#### Scenario: 返回固定话术

- **WHEN** 意图为 OUT_OF_SCOPE
- **THEN** speechText 等于上述固定文案，displayBlocks 为空列表

### Requirement: 合规兜底占位 ensureCompliant

系统 SHALL 在分支结束、写入记忆与 session_state 之前，调用包级私有方法 `ensureCompliant(sessionId, userId, emotionResult)`。本次实现 MUST 仅打印 INFO 级别日志后透传 emotionResult，不修改其内容。

#### Scenario: 当前实现透传 EmotionResult

- **WHEN** ensureCompliant 接收任意 emotionResult
- **THEN** 返回的对象与传入对象 `==` 引用相等

### Requirement: session_state 全字段及时更新

系统 SHALL 在 handle 返回前更新 session_state 的下列字段：

| 字段 | 写入规则 |
|------|---------|
| phase | CLARIFY-ASK 时 = `CLARIFY`；PRODUCT_RECOMMENDATION / PRODUCT_COMPARE = `RECOMMEND`；ORDER_CONFIRM = `ORDER_CONFIRM`；CHITCHAT / OUT_OF_SCOPE = `INTENT` |
| currentIntent | 矫正后的意图枚举名 |
| slots | 合并后 + 本分支注入字段（如 priceMin / excludeProductIds）的最终 slots |
| pendingAsk | CLARIFY-ASK 时 = 追问文本；其他分支 = null |
| turnCount | 上一轮值 + 1（首次为 1） |
| lastRecommendations | PRODUCT_RECOMMENDATION 或 PRODUCT_COMPARE 拿到非空 RecommendResult.items 时 = `LastRecommendationsSnapshot.from(items)` 序列化结果；其他分支不修改 |

#### Scenario: 推荐分支更新 last_recommendations

- **WHEN** PRODUCT_RECOMMENDATION 得到 3 件商品
- **THEN** session_state.lastRecommendations 包含 items / minPrice / maxPrice / productIds 四个字段，且持久化到 PG 与 Redis

#### Scenario: 闲聊分支不修改 last_recommendations

- **WHEN** 意图为 CHITCHAT
- **THEN** session_state.lastRecommendations 字段保持上一轮值不变

#### Scenario: turnCount 单调递增

- **WHEN** 同一 sessionId 连续两次 handle
- **THEN** 第二次写入的 turnCount 比第一次大 1

### Requirement: 多视角点评团开关

系统 SHALL 通过 Spring 配置 `voice-shopping.perspective.enabled`（boolean，默认 false）决定是否启用 PerspectiveHubService。

- 开关 false：MUST NOT 调用 PerspectiveHubService。
- 开关 true：MUST 仅在 PRODUCT_RECOMMENDATION 分支、商品推荐之后、情感应答之前调用一次。

#### Scenario: 开关关闭时不调用

- **WHEN** voice-shopping.perspective.enabled = false 且意图为 PRODUCT_RECOMMENDATION
- **THEN** PerspectiveHubService.discuss 未被调用

#### Scenario: 开关开启时拼接到 utterance

- **WHEN** voice-shopping.perspective.enabled = true，PerspectiveHubService 返回非空多视角文本 "价格顾问：xxx\n专业用户：yyy\n入门买家：zzz"
- **THEN** EmotionService.wrap 接收的 utterance 参数 = 原始 utterance + "\n\n[多视角点评]\n" + 该文本

### Requirement: LastRecommendationsSnapshot DTO

系统 SHALL 在 `voice-shopping-common` 模块的 `com.voiceshopping.common.dto.agent` 包下新增 `LastRecommendationsSnapshot` record：

```java
public record LastRecommendationsSnapshot(
    List<RecommendedItem> items,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    List<Long> productIds
) {
    public static LastRecommendationsSnapshot from(List<RecommendedItem> items) { ... }
}
```

`from` 静态工厂 MUST 计算 items 的 minPrice / maxPrice 与 productIds 列表。空列表时返回空快照（min/max 为 null，productIds 为空 list）。

#### Scenario: from 工厂计算极值

- **WHEN** items 含价格 [479, 599, 379]
- **THEN** snapshot.minPrice = 379，snapshot.maxPrice = 599，productIds 顺序与 items 一致
