## ADDED Requirements

### Requirement: TURN 角色取值

系统 SHALL 扩展 `ShortTermMemory.Turn.role` 字段的合法取值集合，新增 `"TURN"` 取值，表示一轮已完结的合并摘要。完整合法取值集合更新为：`USER` / `ASSISTANT` / `SYSTEM` / `TURN`。

#### Scenario: TURN 角色可被序列化与反序列化
- **WHEN** `Turn(role="TURN", content="[PRODUCT_RECOMMENDATION] 用户：想买跑鞋 / 助手：推荐 Nike Air", agent="PRODUCT_RECOMMENDATION", timestamp=now)` 被写入 Redis
- **THEN** `recent(sessionId, 1)` 返回的 Turn 与原对象 `equals` 相等

### Requirement: TurnSummarizer 组件

系统 SHALL 在 `com.voiceshopping.business.memory` 包下提供 `TurnSummarizer` 组件，对外提供方法 `String summarize(String userUtterance, IntentEnum intent, String agentReply)`。返回值 MUST 严格按 `"[%s] 用户：%s / 助手：%s"` 模板格式化，三个占位符依次为 `intent.name()`、`userUtterance`、`agentReply`。

#### Scenario: 标准摘要格式
- **WHEN** `summarize("想买跑鞋", IntentEnum.PRODUCT_RECOMMENDATION, "推荐 Nike Air Zoom")` 被调用
- **THEN** 返回字符串严格为 `"[PRODUCT_RECOMMENDATION] 用户：想买跑鞋 / 助手：推荐 Nike Air Zoom"`

#### Scenario: 入参非空校验
- **WHEN** `summarize(null, IntentEnum.CHITCHAT, "嗯嗯")` 被调用
- **THEN** 抛出 `IllegalArgumentException`（fail-fast，不允许 null 用户输入）

#### Scenario: 助手回复为空字符串保留
- **WHEN** `summarize("...", intent, "")` 被调用
- **THEN** 返回字符串 `"[xxx] 用户：... / 助手："` （末尾空，不抛异常）

## MODIFIED Requirements

### Requirement: ShortTermMemory recent

The system SHALL provide a `recent(sessionId, n)` method that returns the most recent `n` Turns from the Redis List, ordered from oldest to newest. 返回的 Turn 列表中 role 取值集合 MUST 反映新的合法集合：`USER` / `ASSISTANT` / `SYSTEM` / `TURN`。下游消费方（IntentService、EmotionService）在拼接 prompt 时 MUST 容忍 TURN 摘要型条目与传统 USER/ASSISTANT 条目混合存在的情形。

#### Scenario: Request fewer turns than stored
- **WHEN** `recent(sessionId, 3)` is called and the list has 10 entries
- **THEN** the 3 most recent Turns are returned (LRANGE with negative index)

#### Scenario: Request more turns than stored
- **WHEN** `recent(sessionId, 10)` is called and the list has 3 entries
- **THEN** all 3 Turns are returned

#### Scenario: No memory exists for session
- **WHEN** `recent(sessionId, 5)` is called and no Redis key exists
- **THEN** an empty list is returned

#### Scenario: IntentAgent reads recent 3 turns for context
- **WHEN** IntentAgent calls `recent(sessionId, 3)` before processing a new user utterance
- **THEN** it receives the last 3 Turns (regardless of role/agent) to use as conversation context

#### Scenario: EmotionAgent reads recent 2 turns for mood trend
- **WHEN** EmotionAgent calls `recent(sessionId, 2)` before generating an emotional response
- **THEN** it receives the last 2 Turns to assess user mood trajectory

#### Scenario: Orchestrator appends each turn result
- **WHEN** the Orchestrator completes processing a user turn (ASR result, Agent output, etc.)
- **THEN** it calls `append(sessionId, turn)` with the appropriate role, agent name, and content

#### Scenario: TURN 摘要混入历史窗口
- **WHEN** 短期记忆中混合存在 USER / ASSISTANT / TURN 三种 role 的条目，IntentService 调用 `recent(sessionId, 3)`
- **THEN** 返回的 3 条 Turn 按时间序无歧义返回，不因新增 TURN 角色而过滤或抛异常

### Requirement: Turn record

The system SHALL define an inner `Turn` record in `ShortTermMemory` with fields: `role` (String，合法取值 `USER` / `ASSISTANT` / `SYSTEM` / `TURN`), `content` (String), `turn` (int), `agent` (String, nullable — identifies which agent produced this turn, e.g. "IntentAgent", "RecAgent"; null for USER turns; for TURN role 此字段为该轮最终矫正后的 IntentEnum.name()), `timestamp` (Instant)。

#### Scenario: Turn serialization
- **WHEN** a Turn is serialized to JSON and stored in Redis
- **THEN** it can be deserialized back to an identical Turn instance

#### Scenario: TURN 角色 agent 字段持有 IntentEnum.name
- **WHEN** Orchestrator 写入 TURN 摘要 `Turn(role="TURN", content=..., agent="PRODUCT_RECOMMENDATION", timestamp=...)`
- **THEN** Redis 反序列化后 agent 字段值仍为 "PRODUCT_RECOMMENDATION"，可用于下游分析与调试
