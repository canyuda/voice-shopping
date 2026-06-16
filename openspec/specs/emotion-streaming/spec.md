## Purpose

EmotionStreamingService 将情感包装 Agent 的字级流式输出转换为可下发、可合成的文本流；SentenceAggregator 将字级流聚合为句子级合成单元，降低 H5 首响与 TTS 播放延迟。

## Requirements

### Requirement: EmotionStreamingService 流式包装

系统 SHALL 在 `com.voiceshopping.business.agent` 包下提供 `EmotionStreamingService`，提供流式包装方法：

```java
Flux<String> streamWrap(String sessionId, String userUtterance, String userNeeds, RecommendResult rec);
```

方法 SHALL：
1. 调 `SessionMoodDetector.detect(sessionId, userUtterance)` 取 mood。
2. 构造 userMsg（与 EmotionService.buildUserMsg 相同逻辑，含 userNeeds + 裸数据 products）。
3. 经 `AgentFactory.getEmotionAgent(sessionId)` 获取 agent，调用 `agent.stream(userMsg)` 获取字级 `Flux<Event>`。
4. 事件分流：
   - `EventType.REASONING && !isLast` → 提取 textContent，作为字级文本 emit 给下游
   - `EventType.AGENT_RESULT` → 从 `event.getMessage().getChatUsage()` 提取 ChatUsage 用于成本埋点，但不进入文本流（避免重复播报）
   - 其他事件 → 过滤忽略
5. 在 AGENT_RESULT 事件 hook 中调用 `CostMetricsLogger.logLlm`（agent="emotion_stream"），保证只产生一条流式总埋点。
6. 在流开始前执行 `AgentMemoryPolicy.beforeEmotionCall(agent)`。

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

### Requirement: SentenceAggregator 分句聚合

系统 SHALL 在 `com.voiceshopping.ai.tts` 包下提供 `SentenceAggregator`，将字级 `Flux<String>` 聚合为可合成单元（句子级）。

聚合规则：
1. **确定断点**（`。！？`）：立即 flush 当前缓冲区为一个完整句子。
2. **候选断点**（`，、；`）：启动定时器（默认 50ms，由 `voice-shopping.streaming.sentence-aggregate-timeout-ms` 配置），若超时前无新字到来则 flush；若有新字则继续缓冲。
3. **非标点字符**：追加到当前缓冲区。
4. **流结束**：flush 缓冲区中的剩余文本（即使无标点结尾）。

```java
static Flux<String> aggregate(Flux<String> charFlux, Duration candidateTimeout);
```

#### Scenario: 句号立即切分
- **WHEN** 字级流 emit "好"，"给你"，"挑了三款"，"。"
- **THEN** 聚合后 emit "好给你挑了三款。"，无需等待超时

#### Scenario: 逗号 + 50ms 无新字 → 切分
- **WHEN** 字级流 emit "别纠结"，"，" 后 50ms 内无新字
- **THEN** 聚合后 emit "别纠结，"

#### Scenario: 逗号 + 50ms 内有新字 → 继续缓冲
- **WHEN** 字级流 emit "别纠结"，"，" 后 30ms 内有新字 ""
- **THEN** 不在逗号处切分，继续缓冲直到遇到确定断点或超时

#### Scenario: 流结束 flush 剩余
- **WHEN** 字级流最后 emit "你看看选哪个？" 后流完成，无尾部标点
- **THEN** flush 剩余文本 "你看看选哪个？"

### Requirement: durationMs 字段在流式场景的语义

EmotionStreamingService 埋点的 `durationMs` MUST 反映**从 streamWrap 方法入口到 AGENT_RESULT 事件到达**的总时长。

#### Scenario: durationMs 包含整个流式生成时间
- **WHEN** streamWrap 方法 t0 = System.currentTimeMillis()，AGENT_RESULT 收到时 t1
- **THEN** 埋点 durationMs = t1 - t0
- **THEN** 通常等于 LLM 流式生成完整响应的总时间（首字延迟 + 流式输出 + 末尾完整重放）
