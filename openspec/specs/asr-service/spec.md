## Purpose

ASRService 流式语音识别能力，封装 DashScope 实时识别连接、音频帧推送、资源清理与成本埋点。

## Requirements

### Requirement: ASRService 流式语音识别
ASRService SHALL 封装 DashScope SDK `Recognition`，对外暴露三个方法：`start()` 返回 `Flowable<RecognitionResult>`、`sendFrame(byte[] pcm)` 推送音频帧、`stop()` 结束识别。模型 SHALL 为 `paraformer-realtime-v1`，格式 `pcm`，采样率 16000Hz。

#### Scenario: 正常识别流程
- **WHEN** 调用 `start()` 建立 ASR 连接
- **THEN** 返回的 Flowable 开始接收 RecognitionResult
- **WHEN** 调用 `sendFrame(pcmData)` 推送 16kHz PCM 音频帧
- **THEN** 帧被发送到 Paraformer 进行实时识别
- **WHEN** 调用 `stop()`
- **THEN** ASR 连接正常关闭，Flowable 发射 onComplete

#### Scenario: 增量识别结果
- **WHEN** ASR 返回 `isSentenceEnd=false` 的结果
- **THEN** 通过 Flowable 发射该结果，`getSentence().getText()` 包含当前增量文字

#### Scenario: 句结束结果
- **WHEN** ASR 返回 `isSentenceEnd=true` 的结果
- **THEN** 通过 Flowable 发射该结果，`getSentence().getText()` 包含完整句子文字

#### Scenario: 重复调用防护
- **WHEN** 在未调用 `start()` 的情况下调用 `sendFrame()`
- **THEN** 抛出 IllegalStateException

#### Scenario: 连接异常处理
- **WHEN** DashScope SDK 回调 onError
- **THEN** Flowable 发射 onError，ASRService 内部状态重置为 IDLE

### Requirement: ASRService 资源清理
ASRService SHALL 在 `stop()` 后释放 Recognition 实例资源。后续 `start()` 调用 SHALL 创建新的 Recognition 实例。

#### Scenario: 多次 start/stop 循环
- **WHEN** 调用 `start()` → `sendFrame()` → `stop()` → `start()`
- **THEN** 第二次 `start()` 创建新连接，不残留上一次状态

### Requirement: ASRService 调用埋点

`ASRService` SHALL 在 ASR session 完整结束（`onComplete` / `stop()` 触发）时通过 `CostMetricsLogger.logAsr` 输出成本日志。

字段约束：
- `scene`: `ASR`
- `sessionId` / `userId`: 来自 WebSocket handshake 注入到 ASR 上下文（需要从 VoiceWebSocketHandler 透传，或 ASRService 持有该会话的 sessionId/userId 引用）
- `model`: 当前 ASR 模型名（如 `paraformer-realtime-v2`）
- `audioMs`: 该 session 累计接收的音频时长（毫秒）。可由 ASRService 内部累加每帧的时长（PCM 帧数 × 每帧时长）得出
- `durationMs`: ASR session 从 start 到 onComplete 的总耗时

埋点 MUST 在 session 结束时仅产生 1 条日志（每次 WebSocket 连接对应 1 个 ASR session 对应 1 条埋点）。

#### Scenario: ASR session 结束触发埋点
- **WHEN** WebSocket 关闭 / ASRService.stop() 调用 / DashScope 主动 onComplete
- **THEN** cost-metrics.log 出现 `scene=ASR sessionId=<id> userId=<id> model=paraformer-realtime-v2 audioMs=<n> durationMs=<m>`
- **THEN** 整个 session 仅产生 1 条 ASR 埋点（不是每帧一条）

#### Scenario: audioMs 反映累计音频时长
- **WHEN** 用户说了 5 秒话，前端发送 50 帧音频（每帧 100ms）
- **THEN** audioMs ≈ 5000 ms

#### Scenario: 短连接（用户立即停止）
- **WHEN** 用户连接后立即点击停止，仅发了 800ms 静音补帧
- **THEN** audioMs ≈ 800
