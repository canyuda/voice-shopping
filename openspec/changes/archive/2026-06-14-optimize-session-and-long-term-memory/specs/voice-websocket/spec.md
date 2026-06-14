## MODIFIED Requirements

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
