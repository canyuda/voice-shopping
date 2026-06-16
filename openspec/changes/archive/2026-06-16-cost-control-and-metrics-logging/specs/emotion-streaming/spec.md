## MODIFIED Requirements

### Requirement: EmotionStreamingService 流式包装

系统 SHALL 在 `com.voiceshopping.business.agent` 包下提供 `EmotionStreamingService`，提供流式包装方法：

```java
Flux<String> streamWrap(String sessionId, String userUtterance, String userNeeds, RecommendResult rec);
```

方法 SHALL：
1. 调 `SessionMoodDetector.detect(sessionId, userUtterance)` 取 mood
2. 构造 userMsg（同 EmotionService.buildUserMsg 逻辑，含 userNeeds + 裸数据 products）
3. 经 `AgentFactory.getEmotionAgent(sessionId)` 获取 agent，调用 `agent.stream(userMessage)` 获取字级 `Flux<Event>`
4. **[CHANGED]** 事件分流：
   - `EventType.REASONING && !isLast` → 提取 textContent，作为字级文本 emit 给下游
   - `EventType.AGENT_RESULT` → **从 `event.getMessage().getChatUsage()` 提取 ChatUsage 用于成本埋点**，但**不**进入文本流（避免重复播报）
   - 其他事件 → 过滤忽略
5. 在 AGENT_RESULT 事件 hook 中调用 `CostMetricsLogger.logLlm`（agent="emotion_stream"），保证只产生一条流式总埋点
6. 流开始前执行 `AgentMemoryPolicy.beforeEmotionCall(agent)`

#### Scenario: 字级流仅含 REASONING 增量
- **WHEN** AgentScope SDK 发出 REASONING+isLast=false / REASONING+isLast=true / AGENT_RESULT 三种事件
- **THEN** 下游 Flux<String> 仅收到 REASONING+isLast=false 的 textContent
- **THEN** 不出现"完整重放文本"的字符串

#### Scenario: AGENT_RESULT 事件触发成本埋点
- **WHEN** AgentScope SDK 发出 AGENT_RESULT 事件
- **THEN** `CostMetricsLogger.logLlm` 被调用一次，agent="emotion_stream"
- **THEN** inputTokens/outputTokens/totalTokens 来自 `event.getMessage().getChatUsage()`

#### Scenario: 流式异常时仍埋点
- **WHEN** agent.stream 抛异常，走 onErrorResume 返回 fallback 单元素流
- **THEN** 如果 AGENT_RESULT 已经收到，已正常埋点
- **THEN** 如果 AGENT_RESULT 未收到（异常发生在中途），不产生埋点（成本已发生但 ChatUsage 不可得，记 WARN 日志告警）

## ADDED Requirements

### Requirement: durationMs 字段在流式场景的语义

EmotionStreamingService 埋点的 `durationMs` MUST 反映**从 streamWrap 方法入口到 AGENT_RESULT 事件到达**的总时长。

#### Scenario: durationMs 包含整个流式生成时间
- **WHEN** streamWrap 方法 t0 = System.currentTimeMillis()，AGENT_RESULT 收到时 t1
- **THEN** 埋点 durationMs = t1 - t0
- **THEN** 通常等于 LLM 流式生成完整响应的总时间（首字延迟 + 流式输出 + 末尾完整重放）
