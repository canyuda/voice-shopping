# orchestrator-service

## Purpose

Single-entry orchestrator capability for the voice shopping assistant. `OrchestratorService.handle` is the only inbound method downstream callers (WebSocket / Controller) use to drive a full conversational turn: intent understanding → fallback intent revision → branch dispatch (PRODUCT_RECOMMENDATION / CLARIFY_NEEDED / PRODUCT_COMPARE / ORDER_CONFIRM / CHITCHAT / OUT_OF_SCOPE) → compliance pass-through → short-term memory and `session_state` writeback → EmotionResult.

## Requirements

### Requirement: OrchestratorService 单一入口

系统 SHALL 在 `com.voiceshopping.business.orchestrator` 包下提供 `OrchestratorService`，对外暴露两个方法：

```java
EmotionResult handle(String sessionId, Long userId, String utterance);
Flux<StreamChunk> streamHandle(String sessionId, Long userId, String utterance);
```

`handle()` 方法保持原有同步行为不变，用于 HTTP 调试接口等场景。该方法 MUST 串联意图理解、意图兜底矫正、按意图分支执行、合规兜底、记忆/状态写回，并返回最终 EmotionResult。
`streamHandle()` 方法提供流式输出，具体帧协议和流式后置行为详见 `stream-chunk-protocol` capability spec。

#### Scenario: handle 仍可同步调用

- **WHEN** `handle("sess-1", 100L, "想买双跑鞋")` 被调用且 session 已存在
- **THEN** 方法返回非空 EmotionResult，行为与修改前完全一致，session_state 已被更新到 PG/Redis，短期记忆中已写入对应的 ASSISTANT 与 TURN turn

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

系统 SHALL 在 handle 中向 `ShortTermMemory` 按以下顺序写入 turn：

1. **不再**在意图理解之前写入 USER turn（旧版本中第 154 行的 `shortTermMemory.append("USER", utterance, ...)` MUST 被删除）。本轮当前 utterance 已由方法入参直接传给 IntentService，无需重复入库。
2. 在 EmotionResult 计算完成、合规检查通过后，写入 ASSISTANT turn（content = `safeReply.speechText()`，agent = `finalIntent.name()`，timestamp = 当前时间）。
3. 紧接 ASSISTANT turn 之后，调用注入的 `TurnSummarizer.summarize(utterance, finalIntent, safeReply.speechText())` 生成摘要，写入第二条 turn：role = `"TURN"`、content = 摘要、agent = `finalIntent.name()`、timestamp = 当前时间。

> 设计说明：保留 ASSISTANT 用于其他可观测/调试场景；新增 TURN 摘要作为下一轮 IntentService 的紧凑历史输入。删除 USER 是为了避免与 TURN 在 `recent(3)` 视窗内重复占额度。

#### Scenario: 不再前置写入 USER turn
- **WHEN** handle 进入 IntentService.classify 之前
- **THEN** ShortTermMemory 中本轮 USER 角色 turn 数量为 0（与本轮相比无新增 USER turn）

#### Scenario: ASSISTANT 与 TURN 顺序追加
- **WHEN** handle 处理完成且 finalIntent = PRODUCT_RECOMMENDATION，safeReply.speechText = "推荐 Nike Air"
- **THEN** ShortTermMemory 中本轮新增的 turn 列表（按时间序）为：
  1. `Turn(role="ASSISTANT", content="推荐 Nike Air", agent="PRODUCT_RECOMMENDATION", ts=...)`
  2. `Turn(role="TURN", content="[PRODUCT_RECOMMENDATION] 用户：... / 助手：推荐 Nike Air", agent="PRODUCT_RECOMMENDATION", ts=...)`

#### Scenario: TurnSummarizer 调用参数正确
- **WHEN** handle 接收 utterance="想买跑鞋"，finalIntent=PRODUCT_RECOMMENDATION，safeReply.speechText="推荐 Nike Air"
- **THEN** TurnSummarizer.summarize 被调用一次，参数顺序为 ("想买跑鞋", PRODUCT_RECOMMENDATION, "推荐 Nike Air")

#### Scenario: 异常路径不写入 ASSISTANT 与 TURN
- **WHEN** handle 在 dispatch 阶段抛异常
- **THEN** ShortTermMemory 不新增 ASSISTANT 或 TURN 任一条目（仅 finally 块的 Timer stop 被执行）

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
4. 调用 `EmotionService.wrap(sessionId, augmentedUtterance, userNeeds, recommendResult)` 得到 EmotionResult。userNeeds 由 mergedSlots 转换而来，格式为 `key1=val1,key2=val2,...`。
5. 用 RecommendResult 的 items 计算并写入 `last_recommendations` 字段（见对应 requirement）。

#### Scenario: 完整推荐链路传 userNeeds

- **WHEN** 意图为 PRODUCT_RECOMMENDATION，mergedSlots = {category: "跑鞋", budget: 500}，RecommendResult 含 3 件商品
- **THEN** EmotionService.wrap 接收 userNeeds = "category=跑鞋,budget=500"
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

随后调用 `ParallelRecommendService.recommend` → `EmotionService.wrap`。`EmotionService.wrap` 调用 SHALL 传入 userNeeds 参数，由 compareSlots 转换而来。

若 lastRecommendations 缺失或反序列化失败，分支 MUST 退化为 PRODUCT_RECOMMENDATION 完整链路（含 ClarifyService.decide）。

#### Scenario: cheaper 路径用 maxPrice * 0.8 作为新 budget

- **WHEN** lastRecommendations.maxPrice = 599、priceDirection = "cheaper"
- **THEN** 传给 ParallelRecommendService 的 slots.budget = 479.20，excludeProductIds 包含上轮所有 productId

#### Scenario: expensive 路径用 minPrice * 1.2 作为 priceMin 且不污染 budget

- **WHEN** lastRecommendations.minPrice = 379、priceDirection = "expensive"，原 slots.budget = 1000
- **THEN** 传给 ParallelRecommendService 的 slots.priceMin = 454.80，slots.budget 仍为 1000

#### Scenario: compare 分支传 userNeeds

- **WHEN** PRODUCT_COMPARE 分支，compareSlots = {category: "跑鞋", priceMin: 454.80, excludeProductIds: [...]}
- **THEN** EmotionService.wrap 接收由 compareSlots 转换的 userNeeds

#### Scenario: 上轮 last_recommendations 缺失退化为推荐

- **WHEN** PRODUCT_COMPARE 但 session_state.lastRecommendations = null
- **THEN** 分支退化执行 PRODUCT_RECOMMENDATION 完整链路

### Requirement: ORDER_CONFIRM 分支占位

意图为 ORDER_CONFIRM 时（**或**当前 `session_state.phase == "ORDER_CONFIRM"` 触发 phase 短路时），系统 SHALL 进入订单确认子状态机：

1. 当配置 `voice-shopping.order.enabled = false` 时，系统 SHALL 直接返回 `new EmotionResult("好，给你下单（完整下单逻辑后续补）", List.of())`，按规范写入短期记忆与 session_state（保留旧占位行为作 rollback fallback）
2. 配置启用时（默认），系统 SHALL 调用 `handleOrderConfirm(sessionId, userId, state, utterance)` 完成：
   - 引用项解析（`OrderReferenceResolver`）
   - 预览单生成（`OrderService.preview` + `PendingOrderStore`）
   - YES/NO 二次确认（`containsYes` / `containsNo`，NO 优先）
   - 落单（`OrderService.confirm` + PG 原子扣库存）
   - 取消（`OrderService.cancel`）
3. `handleOrderConfirm` 内部所有分支 MUST 返回 `BranchOutcome`，包括确认成功（phase=`ENDED`）、取消（phase=`RECOMMEND`）、库存被抢的友好回退（phase=`RECOMMEND`）、追问"哪一款？"（phase 保持 `ORDER_CONFIRM`）

完整子状态机行为详见 `order-placement-flow` capability spec。

#### Scenario: 开关启用 + pending 存在 + YES 成功落单
- **WHEN** `voice-shopping.order.enabled=true`，session 已有 pending，utterance="确认下单"
- **THEN** EmotionResult.speechText 以 "下单成功，订单尾号 " 开头，session_state.phase=ENDED

#### Scenario: 开关启用 + 首次 ORDER_CONFIRM 触发预览
- **WHEN** `voice-shopping.order.enabled=true`，意图首次为 ORDER_CONFIRM 且 state.lastRecommendations 含 3 件商品，utterance="第二款"
- **THEN** OrderService.preview 被调用，Redis pending 已写入，session_state.phase=ORDER_CONFIRM，speechText 含"确认下单吗"

#### Scenario: 开关关闭回到占位话术
- **WHEN** `voice-shopping.order.enabled=false`，意图为 ORDER_CONFIRM
- **THEN** EmotionResult.speechText = "好，给你下单（完整下单逻辑后续补）"，OrderService.preview/confirm 均未被调用

#### Scenario: 短期记忆与 session_state 仍按规范写入
- **WHEN** 订单分支以任意路径返回
- **THEN** ShortTermMemory 中按规范追加 ASSISTANT 和 TURN turn，session_state 按 BranchOutcome 写回

### Requirement: handle 入口 phase=ORDER_CONFIRM 短路

系统 SHALL 在 `handle` 入口加载 `session_state` 后立即检查 phase：

- 若 `state.phase == "ORDER_CONFIRM"` 且 `voice-shopping.order.enabled=true`：
  1. 调用 `handleOrderConfirm(sessionId, userId, state, utterance)`，**跳过 IntentService 与 reviseIntent**
  2. 若返回非 null `BranchOutcome` → 走 compliance / 记忆 / 状态写回正常流程，意图标签写 `ORDER_CONFIRM`
  3. 若返回 null（pending 已过期且 resolver 失败）→ 将 `state.phase` 改为 `RECOMMEND`，**继续 fall through** 到 IntentService 正常意图链路
- 若 `state.phase != "ORDER_CONFIRM"` 或开关关闭 → 走原有 IntentService → reviseIntent → dispatch 链路

#### Scenario: 短路跳过 IntentService
- **WHEN** state.phase=ORDER_CONFIRM 且 pending 存在 且 utterance="确认"
- **THEN** IntentService.classify 未被调用一次

#### Scenario: pending 过期 + 无法 resolve → 回退后正常分类
- **WHEN** state.phase=ORDER_CONFIRM，Redis pending 已过期，utterance="再给我推荐点贵的"
- **THEN** state.phase 被改为 RECOMMEND，IntentService.classify 被正常调用，最终意图按 IntentService 输出（可能进入 PRODUCT_COMPARE 等）

#### Scenario: 开关关闭时不短路
- **WHEN** `voice-shopping.order.enabled=false`，state.phase=ORDER_CONFIRM
- **THEN** IntentService.classify 被正常调用，handleOrderConfirm 未被调用

#### Scenario: 短路路径 timer tag 仍为 ORDER_CONFIRM
- **WHEN** 走短路且 handleOrderConfirm 返回正常 BranchOutcome
- **THEN** `voice.shopping.orchestrator.handle` Timer 以 `intent=ORDER_CONFIRM` 记录耗时

### Requirement: OrchestratorService 新增流式依赖注入

系统 SHALL 在 `OrchestratorService` 构造器追加注入：
- `EmotionStreamingService` —— 新增
- `TTSService` —— 新增（从 ai 模块引入）

依赖 final 字段、构造器注入，与现有依赖注入风格一致。

#### Scenario: 两个依赖完整注入
- **WHEN** Spring 容器启动
- **THEN** OrchestratorService bean 含 emotionStreamingService / ttsService 两个非 null 字段

### Requirement: OrchestratorService 新增订单依赖注入

系统 SHALL 在 `OrchestratorService` 构造器追加注入：
- `OrderService` —— 现有，新增 preview/confirm/cancel 方法
- `PendingOrderStore` —— 新增
- `OrderReferenceResolver` —— 新增

依赖 final 字段、构造器注入，与现有依赖注入风格一致。

#### Scenario: 三个依赖完整注入
- **WHEN** Spring 容器启动
- **THEN** OrchestratorService bean 含 orderService / pendingOrderStore / referenceResolver 三个非 null 字段

### Requirement: CHITCHAT 分支闲聊兜底

意图为 CHITCHAT 时，系统 SHALL 调用 `EmotionService.wrap(sessionId, utterance, "", RecommendResult.EMPTY)`。若 EmotionService 返回的 speechText 为推荐式空兜底（"这个条件下合适的不多，要不要放宽点预算再看看？"）时，系统 SHALL 用闲聊兜底文案替换："不太懂这个，我们聊点你想买啥呗？"。userNeeds 传空字符串。

#### Scenario: 闲聊传空 userNeeds

- **WHEN** 意图为 CHITCHAT
- **THEN** 调用 EmotionService.wrap 时 userNeeds 参数为空字符串 ""，RecommendResult 参数是 RecommendResult.EMPTY

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
