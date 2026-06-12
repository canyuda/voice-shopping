## ADDED Requirements

### Requirement: VoiceWebSocketHandler 全双工处理
VoiceWebSocketHandler SHALL 继承 `AbstractWebSocketHandler`，处理 WebSocket 连接的完整生命周期。上行 BinaryMessage 接收 PCM 音频帧并推送给 ASRService，ASR 句结束后触发 Agent 流程（当前为回显），回显文本送入 TTSService，TTS 输出的 PCM 帧通过 BinaryMessage 下发。

#### Scenario: 完整链路回显
- **WHEN** WebSocket 连接建立
- **THEN** 创建 ASRService 实例并调用 `start()`
- **WHEN** 收到上行 BinaryMessage（PCM 音频帧）
- **THEN** 将帧推送给 `ASRService.sendFrame()`
- **WHEN** ASR 返回 `isSentenceEnd=true` 的结果
- **THEN** 获取完整文本，通过 TextMessage 下发 `AsrFinalResult`
- **THEN** 将文本送入 `TTSService.synthesize()`
- **THEN** TTS 返回的每个 PCM 帧通过 BinaryMessage 下发
- **THEN** TTS 完成后通过 TextMessage 下发 `AgentStatus(status="done")`

#### Scenario: ASR 增量结果转发
- **WHEN** ASR 返回 `isSentenceEnd=false` 的结果，且 `getText()` 非空
- **THEN** 通过 TextMessage 下发 `AsrPartialResult`

#### Scenario: 连接关闭资源清理
- **WHEN** WebSocket 连接关闭（afterConnectionClosed）
- **THEN** 调用 `ASRService.stop()` 释放 ASR 资源
- **THEN** 日志记录连接关闭

#### Scenario: 发送到已关闭的连接
- **WHEN** 尝试向已关闭的 WebSocket session 发送消息
- **THEN** 跳过发送，日志级别为 warn（非 error）

### Requirement: WebSocketConfig 端点注册
WebSocketConfig SHALL 实现 `WebSocketConfigurer`，注册 `VoiceWebSocketHandler` 到路径 `/ws/voice`。调试阶段 SHALL 允许所有 origin。

#### Scenario: 端点可连接
- **WHEN** 浏览器连接 `ws://localhost:8080/ws/voice`
- **THEN** WebSocket 握手成功，连接建立

### Requirement: 下行 JSON 信令 DTO
系统 SHALL 定义 4 个 Record 类型用于下行 JSON 信令：`AsrPartialResult(type="asr_partial", text)`、`AsrFinalResult(type="asr_final", text)`、`AgentStatus(type="agent_status", status)`、`VoiceError(type="error", code, message)`。所有 DTO 定义在 `com.voiceshopping.common.dto` 包下。

#### Scenario: AsrPartialResult 序列化
- **WHEN** 创建 `new AsrPartialResult("asr_partial", "你好")`
- **THEN** Jackson 序列化为 `{"type":"asr_partial","text":"你好"}`

#### Scenario: AsrFinalResult 序列化
- **WHEN** 创建 `new AsrFinalResult("asr_final", "你好我想买个手表")`
- **THEN** Jackson 序列化为 `{"type":"asr_final","text":"你好我想买个手表"}`

#### Scenario: VoiceError 序列化
- **WHEN** 创建 `new VoiceError("error", "ASR_ERROR", "连接超时")`
- **THEN** Jackson 序列化为 `{"type":"error","code":"ASR_ERROR","message":"连接超时"}`

### Requirement: Agent 接入点预留
VoiceWebSocketHandler 在 ASR 句结束到 TTS 合成之间 SHALL 预留 Agent 调用点，标记 `// TODO: Agent integration`。当前阶段直接将 ASR 文本原样传递给 TTS。

#### Scenario: 回显模式
- **WHEN** ASR 返回句结束文本 "你好我想买个手表"
- **THEN** 直接将 "你好我想买个手表" 送入 TTSService，不经任何 Agent 处理
