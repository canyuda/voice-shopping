# voice-websocket

## Purpose

Voice 全双工 WebSocket 端点：上行 PCM 音频帧由 ASR 流式识别，句结束触发 Agent 流程，回复经 TTS 合成后下行 PCM 帧；同时承载握手鉴权（Sa-Token sub-protocol token）、连接生命周期管理、长期记忆回流触发、Agent 缓存释放等横切职责。

## Requirements

### Requirement: VoiceWebSocketHandler 全双工处理
VoiceWebSocketHandler SHALL 继承 `AbstractWebSocketHandler`，处理 WebSocket 连接的完整生命周期。上行 BinaryMessage 接收 PCM 音频帧并推送给 ASRService，ASR 句结束后触发 Agent 流程，Agent 回复送入 TTSService，TTS 输出的 PCM 帧通过 BinaryMessage 下发。

连接关闭时 MUST 同时执行下列三件事（顺序固定，前两件在第三件之前）：

1. `ASRService.stop()` 释放 ASR 流（按 `wsId` 索引的 `sessionAsrMap`）
2. 当 handshake attributes 中 `bizSessionId` 与 `userId` 都非空时，`longTermMemoryWriter.flushOnSessionEnd(bizSessionId, userId)` 触发跨会话长期记忆回流；本版本 LongTermMemoryWriter **不内置幂等去重**（`docs/short-term-memory-archive.md` / `long-term-memory-writeback` spec 中"多源触发幂等性"说明），调用方亦不在此进行 ShortTermMemory clear 等防重操作；任何异常 MUST 被 try/catch 包裹仅 WARN 日志记录
3. 当 `bizSessionId` 非空时，`AgentFactory.remove(bizSessionId)` 释放该会话缓存的 4 个 Agent 实例

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

#### Scenario: 连接关闭释放 ASR 资源
- **WHEN** WebSocket 连接关闭（afterConnectionClosed）
- **THEN** 调用 `ASRService.stop()` 释放 ASR 资源
- **THEN** 日志记录连接关闭

#### Scenario: 连接关闭触发长期记忆回流
- **WHEN** WebSocket 关闭且 attributes 中 `bizSessionId` 与 `userId` 都非空
- **THEN** `longTermMemoryWriter.flushOnSessionEnd(bizSessionId, userId)` 被调用一次

#### Scenario: handshake 缺 userId 跳过回流但仍释放 Agent 缓存
- **WHEN** WebSocket 关闭，attributes 中 `bizSessionId` 非空但 `userId` 为 null
- **THEN** 不调用 flushOnSessionEnd，但仍调用 `agentFactory.remove(bizSessionId)`

#### Scenario: flush 调度异常不影响 close 流程
- **WHEN** `longTermMemoryWriter.flushOnSessionEnd` 抛任意 RuntimeException
- **THEN** afterConnectionClosed 不向上抛出异常，WARN 日志记录后继续执行 `agentFactory.remove(bizSessionId)`

#### Scenario: 连接关闭释放 Agent 缓存
- **WHEN** WebSocket 关闭且 `bizSessionId` 非空
- **THEN** `agentFactory.remove(bizSessionId)` 被调用一次，释放该会话缓存的 4 个 Agent 实例

#### Scenario: 发送到已关闭的连接
- **WHEN** 尝试向已关闭的 WebSocket session 发送消息
- **THEN** 跳过发送，日志级别为 warn（非 error）

### Requirement: WebSocketConfig 端点注册

WebSocketConfig SHALL 实现 `WebSocketConfigurer`，注册 `VoiceWebSocketHandler` 到路径 `/ws/voice`，并 SHALL 注册 `AuthHandshakeInterceptor`（取代旧的 `VoiceHandshakeInterceptor`）作为握手拦截器。调试阶段 SHALL 允许所有 origin。

注册的 sub-protocol 列表 SHALL 包含**通配匹配** `Bearer-*` 形式 —— 由于 Spring `setSupportedProtocols` 不支持通配，实际实现可在拦截器内自行验证 prefix 并通过响应头回写完成 RFC 6455 协商，registry 上不强制声明 supportedProtocols。

#### Scenario: 端点可连接
- **WHEN** 浏览器使用 `new WebSocket("ws://localhost:8080/ws/voice?sessionId=s1", ['Bearer-' + token])` 且 token 合法
- **THEN** WebSocket 握手成功，连接建立，响应头含 `Sec-WebSocket-Protocol: Bearer-{token}`

#### Scenario: 旧 query userId 不再被信任
- **WHEN** 浏览器使用 `new WebSocket("ws://localhost:8080/ws/voice?userId=1&sessionId=s1")` 不带 sub-protocol
- **THEN** 握手失败 401（缺失 sub-protocol token）

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
