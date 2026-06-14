# agent-memory-policy

## Purpose

集中管理四个 Worker Agent（IntentAgent / ClarifyAgent / RecAgent / EmotionAgent）`InMemoryMemory` 清理策略的统一收口。各 Service 在调用 `agent.call(...)` 之前 MUST 通过 `AgentMemoryPolicy` 完成记忆裁剪/清理，禁止业务代码直接操作 Agent 的 InMemoryMemory，避免清理策略散落在各个 Service 中难以维护。

## Requirements

### Requirement: AgentMemoryPolicy 集中管理 Agent 内部记忆

系统 SHALL 在 `com.voiceshopping.business.agent` 包下提供 `AgentMemoryPolicy` 组件，作为四个 Worker Agent（IntentAgent、ClarifyAgent、RecAgent、EmotionAgent）`InMemoryMemory` 清理时机的唯一收口。各 Service 在调用 `agent.call(...)` 前 MUST 通过 AgentMemoryPolicy 提供的 `before*Call` 方法处理记忆，禁止直接调用 `agent.getMemory().clear()` 或 `agent.getMemory().deleteMessage(...)`。

#### Scenario: 单例由 Spring 管理
- **WHEN** 应用启动后
- **THEN** Spring 容器中存在唯一的 `AgentMemoryPolicy` Bean，可被 IntentService / ClarifyService / RecommendReasonService / EmotionService 注入

#### Scenario: 业务代码不直接操作 Memory
- **WHEN** 在 IntentService、ClarifyService、RecommendReasonService、EmotionService 中检索 `agent.getMemory().clear()` 或 `agent.getMemory().deleteMessage(`
- **THEN** 不应出现任何匹配（除 AgentMemoryPolicy 自身代码外）

### Requirement: beforeIntentCall 每轮全清

系统 SHALL 提供 `beforeIntentCall(ReActAgent agent)` 方法，在每次 Intent Agent 调用前对其 `InMemoryMemory` 执行 `clear()`，保证 Intent 判断不受历史轮次干扰。

#### Scenario: Intent Agent 内存被清空
- **WHEN** `policy.beforeIntentCall(agent)` 被调用，agent 的 InMemoryMemory 中已有 5 条历史消息
- **THEN** 调用结束后 InMemoryMemory 中消息数量为 0

### Requirement: beforeClarifyCall 每轮全清

系统 SHALL 提供 `beforeClarifyCall(ReActAgent agent)` 方法，在每次 Clarify Agent 调用前对其 `InMemoryMemory` 执行 `clear()`。理由：澄清逻辑每轮拿当前 slots 重新算，不依赖历史。

#### Scenario: Clarify Agent 内存被清空
- **WHEN** `policy.beforeClarifyCall(agent)` 被调用，agent 的 InMemoryMemory 中已有 3 条历史消息
- **THEN** 调用结束后 InMemoryMemory 中消息数量为 0

### Requirement: beforeRecommendCall 裁剪到 8 条

系统 SHALL 提供 `beforeRecommendCall(ReActAgent agent)` 方法，在每次 Rec Agent 调用前对其 `InMemoryMemory` 执行裁剪，保留最后 8 条消息（约 4 轮 user/assistant 对）。裁剪 MUST 通过私有方法 `trimToLast(agent, limit, tag)` 用 `deleteMessage(0)` 循环从头删除实现。

#### Scenario: 当前不超 8 条不裁剪
- **WHEN** Rec Agent 内存中有 5 条消息，`beforeRecommendCall` 被调用
- **THEN** 调用结束后内存中仍为 5 条消息

#### Scenario: 当前超过 8 条裁剪到 8 条
- **WHEN** Rec Agent 内存中有 12 条消息，`beforeRecommendCall` 被调用
- **THEN** 调用结束后内存中保留最后 8 条消息（最早的 4 条被删除）

### Requirement: beforeEmotionCall 裁剪到 40 条

系统 SHALL 提供 `beforeEmotionCall(ReActAgent agent)` 方法，在每次 Emotion Agent 调用前对其 `InMemoryMemory` 执行裁剪，保留最后 40 条消息（约 20 轮 user/assistant 对），用于支持情绪追踪所需的更长上下文。

#### Scenario: 当前不超 40 条不裁剪
- **WHEN** Emotion Agent 内存中有 30 条消息，`beforeEmotionCall` 被调用
- **THEN** 调用结束后内存中仍为 30 条消息

#### Scenario: 当前超过 40 条裁剪到 40 条
- **WHEN** Emotion Agent 内存中有 60 条消息，`beforeEmotionCall` 被调用
- **THEN** 调用结束后内存中保留最后 40 条消息

### Requirement: trimToLast 私有方法实现

AgentMemoryPolicy 内部 SHALL 提供私有方法 `trimToLast(ReActAgent agent, int limit, String tag)`：循环调用 `agent.getMemory().deleteMessage(0)` 从头部删除消息，直到剩余消息数量等于或小于 `limit`。`tag` 参数仅用于 DEBUG 日志（识别是哪个 Agent 的裁剪），不影响逻辑。

#### Scenario: limit 为 0 等价于全清
- **WHEN** `trimToLast(agent, 0, "test")` 被调用，agent 内存中有 3 条消息
- **THEN** 调用结束后内存中消息数量为 0

#### Scenario: 内存为空时无副作用
- **WHEN** `trimToLast(agent, 8, "rec")` 被调用，agent 内存为空
- **THEN** 不抛异常，内存保持为空

### Requirement: IntentService 接入 AgentMemoryPolicy

`IntentService.classify` MUST 删除现有 `agent.getMemory().clear()` 直接调用，改为通过注入的 `AgentMemoryPolicy.beforeIntentCall(agent)` 完成清理。清理 MUST 在 `agent.call(...)` 之前执行。

#### Scenario: classify 调用顺序
- **WHEN** `IntentService.classify(sessionId, utterance)` 被调用
- **THEN** 内部调用顺序为：`agentFactory.getIntentAgent` → `policy.beforeIntentCall(agent)` → `agent.call(...)`

### Requirement: ClarifyService 接入 AgentMemoryPolicy

`ClarifyService.decide` MUST 在 ASK 路径下、`agent.call(...)` 之前调用 `AgentMemoryPolicy.beforeClarifyCall(agent)`。当前 `ClarifyService` 中被注释掉的 `// agent.getMemory().clear();` MUST 被删除（不再保留为注释）。

#### Scenario: decide ASK 路径调用顺序
- **WHEN** rule 检查返回缺失槽位，触发 LLM 提问路径
- **THEN** 内部调用顺序为：`agentFactory.getClarifyAgent` → `policy.beforeClarifyCall(agent)` → `agent.call(...)`

### Requirement: RecommendReasonService 接入 AgentMemoryPolicy

`RecommendReasonService.attachReasons` MUST 在 `agent.call(...)` 之前调用 `AgentMemoryPolicy.beforeRecommendCall(agent)`。

#### Scenario: attachReasons 调用顺序
- **WHEN** `attachReasons(sessionId, userNeeds, products)` 被调用且 products 非空
- **THEN** 内部调用顺序为：`agentFactory.getRecAgent` → `policy.beforeRecommendCall(agent)` → `agent.call(...)`

### Requirement: EmotionService 接入 AgentMemoryPolicy

`EmotionService.wrap` MUST 在 `agent.call(...)` 之前调用 `AgentMemoryPolicy.beforeEmotionCall(agent)`。

#### Scenario: wrap 调用顺序
- **WHEN** `EmotionService.wrap(sessionId, utterance, recommendResult)` 被调用
- **THEN** 内部调用顺序为：`agentFactory.getEmotionAgent` → `policy.beforeEmotionCall(agent)` → `agent.call(...)`
