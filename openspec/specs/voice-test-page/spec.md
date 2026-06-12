## ADDED Requirements

### Requirement: 状态机驱动 UI
voice-test.html SHALL 使用状态机管理整条链路：`IDLE → CONNECTING → RECORDING → WAITING → CLOSING → IDLE`。每个状态的按钮文案、颜色、可点击状态 SHALL 自动切换，防止状态错乱。

#### Scenario: 状态转换完整循环
- **WHEN** 用户点击"开始录音"（IDLE 状态）
- **THEN** 状态转为 CONNECTING，建立 WebSocket
- **THEN** WebSocket 打开后状态转为 RECORDING，开始采集音频
- **WHEN** 用户点击"停止录音"（RECORDING 状态）
- **THEN** 状态转为 WAITING，补 800ms 静音帧后停止发送
- **THEN** 收到 ASR final 后状态转为 CLOSING
- **THEN** 处理完成后状态回到 IDLE

#### Scenario: 按钮禁用
- **WHEN** 状态为 CONNECTING 或 WAITING 或 CLOSING
- **THEN** 按钮 SHALL 被禁用，防止重复点击

### Requirement: AudioWorklet 降采样到 16kHz
voice-test.html SHALL 使用 AudioWorklet 内部降采样到 16kHz，不在 `new AudioContext()` 构造函数中指定 sampleRate。

#### Scenario: 浏览器原生采样率为 48kHz
- **WHEN** AudioContext 原生采样率为 48000Hz
- **THEN** AudioWorklet 将 48kHz 数据降采样为 16kHz（每 3 个样本取 1 个）
- **THEN** 输出 PCM 帧为 16kHz 16bit mono

### Requirement: 100ms 帧聚合
voice-test.html SHALL 将 AudioWorklet 产出的音频帧聚合为每 100ms 一帧（1600 samples @ 16kHz）再通过 WebSocket 发送。

#### Scenario: 帧聚合
- **WHEN** AudioWorklet 连续产出 128 samples 的帧
- **THEN** 聚合到 1600 samples 后一次性 WebSocket send
- **THEN** 约 100ms 间隔发送一帧

### Requirement: 实时音量条
voice-test.html SHALL 在 AudioWorklet 中每帧计算 RMS 音量并通过 postMessage 发回主线程，主线程实时更新音量条 UI。

#### Scenario: 说话时音量条响应
- **WHEN** 用户对着麦克风说话
- **THEN** 音量条实时上升
- **WHEN** 停止说话
- **THEN** 音量条回落到静默

### Requirement: 停止后补 800ms 静音帧
voice-test.html SHALL 在用户点击停止后继续发送 800ms 的静音帧（全零 PCM），确保 Paraformer VAD 触发句结束。

#### Scenario: 静音补帧
- **WHEN** 用户点击"停止录音"
- **THEN** 继续发送 8 帧（8 × 100ms = 800ms）全零 PCM
- **THEN** 800ms 后停止发送

### Requirement: userStopped 标志控制关闭
voice-test.html SHALL 使用 `userStopped` 标志位控制 WebSocket 关闭时机。只在用户主动停止后收到的 ASR `isSentenceEnd=true` 才关闭 WebSocket。

#### Scenario: 说话中喘气触发句结束
- **WHEN** 用户说话中间停顿触发 ASR `isSentenceEnd=true`，但 `userStopped` 为 false
- **THEN** 不关闭 WebSocket，继续接收音频
- **WHEN** 用户点击停止，`userStopped` 置 true，后续收到 ASR final
- **THEN** 关闭 WebSocket

### Requirement: playbackTime 无缝播放
voice-test.html SHALL 维护 playbackTime 确保 TTS 多帧 PCM 无缝衔接播放。每帧 `startAt = max(now, lastEnd)`。

#### Scenario: 连续帧无缝播放
- **WHEN** TTS 连续下发多个 PCM 帧（每帧 10~50ms）
- **THEN** 第一帧立即播放，后续帧紧接着上一帧结束时刻播放
- **THEN** 无"喀嗒"切片声

### Requirement: 每次录音前 cleanup
voice-test.html SHALL 在每次 start() 前调用 cleanup()，显式 close/stop 上一次的 AudioContext、WebSocket、MediaStream。

#### Scenario: 连续两次录音
- **WHEN** 用户完成一次录音后立即再次点击"开始录音"
- **THEN** cleanup() 关闭上一次的 AudioContext、WebSocket、MediaStream
- **THEN** 创建新的连接和资源

### Requirement: noiseSuppression 关闭
voice-test.html SHALL 在 `getUserMedia` 中设置 `noiseSuppression: false`，防止 Chrome 内置降噪削弱小音量语音。

#### Scenario: 小音量说话
- **WHEN** 用户以较小音量说话
- **THEN** Chrome 不应用降噪，音频完整发送到后端
