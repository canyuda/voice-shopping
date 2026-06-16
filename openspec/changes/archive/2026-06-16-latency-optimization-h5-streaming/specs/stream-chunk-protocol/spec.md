## ADDED Requirements

### Requirement: StreamChunk 三帧数据结构

系统 SHALL 在 `com.voiceshopping.common.dto.agent` 包下提供 `StreamChunk` record：

```java
public record StreamChunk(Type type, String text, ByteBuffer audio, Object products) {
    public enum Type { TEXT, AUDIO, PRODUCTS }
    public static StreamChunk text(String t) { ... }
    public static StreamChunk audio(ByteBuffer a) { ... }
    public static StreamChunk products(Object p) { ... }
}
```

- `TEXT` 帧：type=TEXT, text=句子文本, audio=null, products=null
- `AUDIO` 帧：type=AUDIO, text=null, audio=PCM ByteBuffer, products=null
- `PRODUCTS` 帧：type=PRODUCTS, text=null, audio=null, products=RecommendResult.items()

#### Scenario: TEXT 帧构造
- **WHEN** 调用 `StreamChunk.text("别纠结，")`
- **THEN** type=TEXT, text="别纠结，", audio=null, products=null

#### Scenario: PRODUCTS 帧构造
- **WHEN** 调用 `StreamChunk.products(itemList)`
- **THEN** type=PRODUCTS, products=itemList, text=null, audio=null

### Requirement: OrchestratorService.streamHandle 流式编排

系统 SHALL 在 `OrchestratorService` 中新增方法：

```java
Flux<StreamChunk> streamHandle(String sessionId, Long userId, String utterance);
```

方法 SHALL 执行与 `handle()` 相同的前置逻辑（session 查找 → event 发布 → state 加载 → phase 短路检查 → intent 分类 → intent 矫正 → dispatch），但 dispatch 后的推荐 + 情感应答阶段改为流式：

1. **产品卡片先行**：推荐管道完成后，立即 emit `StreamChunk.products(rec.items())`。
2. **文字流**：`EmotionStreamingService.streamWrap()` 返回的字级流经 `SentenceAggregator.aggregate()` 聚合为句子，每个句子 emit `StreamChunk.text(sentence)`。
3. **音频流**：每个句子同时送 `TTSService.synthesize(sentence)` 生成 PCM 帧，每帧 emit `StreamChunk.audio(byteBuffer)`。
4. **顺序保证**：用 `Flux.concat(productsFlux, textAudioFlux)` 确保产品卡片在文字流之前到达。

合规模块 SHALL 在每个句子 emit 前，调用 `ComplianceChecker.ensureCompliant()` 逐句检查。违规句子 SHALL 替换为安全占位文本后继续。

#### Scenario: 推荐分支流式输出
- **WHEN** streamHandle 处理 PRODUCT_RECOMMENDATION 分支，推荐返回 3 件商品
- **THEN** 第一个 emit 的 StreamChunk 为 PRODUCTS 类型，含 3 个 item
- **THEN** 后续依次 emit TEXT + AUDIO 交替帧，直到文字流结束

#### Scenario: 非推荐分支无产品帧
- **WHEN** streamHandle 处理 CHITCHAT 分支
- **THEN** 无 PRODUCTS 帧被 emit，仅有 TEXT + AUDIO 帧

#### Scenario: 产品帧先于文字帧
- **WHEN** 推荐管道完成并开始流式输出
- **THEN** PRODUCTS 帧在所有 TEXT 帧之前被 emit

### Requirement: finalizeTurn 流式后置

系统 SHALL 将 `finalizeTurn` 的后置逻辑（合规兜底检查 + 短期记忆写入 + session_state 持久化）移至 `streamHandle` 返回 Flux 的 `doFinally` 回调中执行：

1. 收集完整回复文本（StringBuilder 累积所有 TEXT 帧内容）。
2. 合规后检（兜底，因逐句已检，此处为双重保险）。
3. 写入 ASSISTANT turn 和 TURN summary。
4. 持久化 session_state。
5. **异常路径**：若流中途异常，写一条 fallback ASSISTANT turn（含 fallback 文本），确保下一轮 IntentService 的 recent turns 不缺轮。

#### Scenario: 正常完成写入完整记忆
- **WHEN** streamHandle 的 Flux 正常完成
- **THEN** ShortTermMemory 中写入 ASSISTANT turn（完整回复文本）和 TURN summary

#### Scenario: 流中途异常写入 fallback 记忆
- **WHEN** EmotionAgent 流式输出在第 3 个字时抛异常
- **THEN** ShortTermMemory 中仍写入 ASSISTANT turn（已输出的部分文本 + fallback 后缀）和 TURN summary

### Requirement: WebSocket 下行信令扩展

系统 SHALL 在现有下行 JSON 信令基础上新增 3 种类型：

| 信令类型 | type 字段 | 字段 | 用途 |
|---------|-----------|------|------|
| StreamText | `stream_text` | text | 字幕片段 |
| StreamProducts | `stream_products` | products | 产品卡片列表 |
| StreamDone | `stream_done` | (无额外字段) | 流式输出结束 |

TTS PCM 二进制通道 SHALL 保持不变。

现有信令（`asr_partial` / `asr_final` / `agent_status` / `error`）SHALL 保留兼容。

#### Scenario: StreamText 序列化
- **WHEN** 创建 StreamText signal，text="别纠结，"
- **THEN** Jackson 序列化为 `{"type":"stream_text","text":"别纠结，"}`

#### Scenario: StreamProducts 序列化
- **WHEN** 创建 StreamProducts signal，products=[{productId:1001, name:"Asics GEL-Contend 9", price:479}]
- **THEN** Jackson 序列化为 `{"type":"stream_products","products":[...]}`

#### Scenario: StreamDone 序列化
- **WHEN** 创建 StreamDone signal
- **THEN** Jackson 序列化为 `{"type":"stream_done"}`

### Requirement: VoiceWebSocketHandler 流式接入

`VoiceWebSocketHandler.handleAsrResult()` 在 ASR 句结束后 SHALL 订阅 `orchestratorService.streamHandle(sessionId, userId, text)` 返回的 `Flux<StreamChunk>`：

1. `PRODUCTS` 帧 → 通过 `sendTextSafely` 发送 `StreamProducts` JSON 信令。
2. `TEXT` 帧 → 通过 `sendTextSafely` 发送 `StreamText` JSON 信令。
3. `AUDIO` 帧 → 通过 `sendBinarySafely` 发送 PCM 二进制帧。
4. 流完成 → 发送 `StreamDone` 信令。

异常处理 SHALL 与现有逻辑一致：catch 后发送 `VoiceError` + fallback TTS。

#### Scenario: 流式帧依次发送
- **WHEN** streamHandle emit PRODUCTS → TEXT("别纠结，") → AUDIO(pcm) → TEXT("鸡哥给你挑了三款。") → AUDIO(pcm) → StreamDone
- **THEN** WebSocket 依次发送对应 JSON/Binary 信令

#### Scenario: 流式异常降级
- **WHEN** streamHandle 的 Flux 在 emit TEXT 帧后抛异常
- **THEN** 发送 VoiceError("AGENT_ERROR", message)，并 speak fallback 回复
