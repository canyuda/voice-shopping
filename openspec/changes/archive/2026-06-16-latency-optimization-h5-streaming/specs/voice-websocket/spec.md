## MODIFIED Requirements

### Requirement: VoiceWebSocketHandler 全双工处理

VoiceWebSocketHandler SHALL 继承 `AbstractWebSocketHandler`，处理 WebSocket 连接的完整生命周期。**[CHANGED]** ASR 句结束后触发 `orchestratorService.streamHandle()` 返回 `Flux<StreamChunk>`，订阅后按帧类型发送对应信令。

连接关闭时的资源释放逻辑保持不变（ASR stop → 长期记忆回流 → Agent 缓存释放）。

#### Scenario: 流式帧依次下发
- **WHEN** ASR 返回 `isSentenceEnd=true` 的结果，streamHandle 返回 Flux
- **THEN** PRODUCTS 帧 → 发送 StreamProducts JSON 信令
- **THEN** TEXT 帧 → 发送 StreamText JSON 信令
- **THEN** AUDIO 帧 → 发送 BinaryMessage PCM 帧
- **THEN** 流完成 → 发送 StreamDone 信令

#### Scenario: 流式异常降级
- **WHEN** streamHandle 的 Flux 抛异常
- **THEN** 发送 VoiceError("AGENT_ERROR", message)
- **THEN** speak fallback 回复

### Requirement: 下行 JSON 信令 DTO

系统 SHALL 定义 7 个 Record 类型用于下行 JSON 信令：

原有 4 种保留：`AsrPartialResult(type="asr_partial", text)`、`AsrFinalResult(type="asr_final", text)`、`AgentStatus(type="agent_status", status)`、`VoiceError(type="error", code, message)`。

**[CHANGED]** 新增 3 种流式信令：`StreamText(type="stream_text", text)`、`StreamProducts(type="stream_products", products)`、`StreamDone(type="stream_done")`。

所有 DTO 定义在 `com.voiceshopping.common.dto` 包下。

#### Scenario: StreamText 序列化
- **WHEN** 创建 `new StreamText("别纠结，")`
- **THEN** Jackson 序列化为 `{"type":"stream_text","text":"别纠结，"}`

#### Scenario: StreamProducts 序列化
- **WHEN** 创建 `new StreamProducts(itemList)`
- **THEN** Jackson 序列化为 `{"type":"stream_products","products":[...]}`

#### Scenario: StreamDone 序列化
- **WHEN** 创建 `new StreamDone()`
- **THEN** Jackson 序列化为 `{"type":"stream_done"}`
