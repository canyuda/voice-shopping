## ADDED Requirements

### Requirement: EmotionStreamingService 流式包装

系统 SHALL 在 `com.voiceshopping.business.agent` 包下提供 `EmotionStreamingService`，提供流式包装方法：

```java
Flux<String> streamWrap(String sessionId, String userUtterance, String userNeeds, RecommendResult rec);
```

方法 SHALL：
1. 调 `SessionMoodDetector.detect(sessionId, userUtterance)` 取 mood。
2. 构造 userMsg（与 EmotionService.buildUserMsg 相同逻辑，含 userNeeds + 裸数据 products）。
3. 经 `AgentFactory.getEmotionAgent(sessionId)` 获取 agent，调用 `agent.stream(userMsg)` 获取字级 `Flux<Event>`。
4. 提取每个 Event 的 textContent，过滤 null/空串，返回 `Flux<String>`（字级流）。
5. 在流开始前执行 `AgentMemoryPolicy.beforeEmotionCall(agent)`。

#### Scenario: 正常流式输出
- **WHEN** streamWrap 收到含 3 个 item 的 rec，且 mood 检测为 neutral
- **THEN** 返回的 Flux<String> 按字级别 emit 文本内容，首字延迟 < 300ms

#### Scenario: agent 流式调用失败返回 fallback 单元素流
- **WHEN** agent.stream() 抛异常
- **THEN** 返回 `Flux.just(fallbackText)`，fallbackText 与 EmotionService.fallback(rec) 一致

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
- **WHEN** 字级流 emit "别纠结"，"，" 后 30ms 内有新字 "鸡哥"
- **THEN** 不在逗号处切分，继续缓冲直到遇到确定断点或超时

#### Scenario: 流结束 flush 剩余
- **WHEN** 字级流最后 emit "你看看选哪个？" 后流完成，无尾部标点
- **THEN** flush 剩余文本 "你看看选哪个？"
