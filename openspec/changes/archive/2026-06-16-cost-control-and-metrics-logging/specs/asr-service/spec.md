## ADDED Requirements

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
