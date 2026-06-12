## 1. 依赖清理

- [x] 1.1 从 `voice-shopping-ai/pom.xml` 移除 `nls-sdk-common`、`nls-sdk-transcriber`、`nls-sdk-tts` 三个依赖
- [x] 1.2 执行 `mvn compile` 确认编译通过

## 2. 公共 DTO

- [x] 2.1 创建 `AsrPartialResult` Record（type="asr_partial", text）在 `com.voiceshopping.common.dto`
- [x] 2.2 创建 `AsrFinalResult` Record（type="asr_final", text）在 `com.voiceshopping.common.dto`
- [x] 2.3 创建 `AgentStatus` Record（type="agent_status", status）在 `com.voiceshopping.common.dto`
- [x] 2.4 创建 `VoiceError` Record（type="error", code, message）在 `com.voiceshopping.common.dto`

## 3. ASRService

- [x] 3.1 创建 `com.voiceshopping.ai.asr.ASRService`，封装 DashScope `Recognition`
- [x] 3.2 实现 `start()` 方法：构造 `RecognitionParam`（paraformer-realtime-v1, pcm, 16000Hz），调用 `Recognition.call(param, callback)`，callback 通过 `PublishProcessor` 下发结果
- [x] 3.3 实现 `sendFrame(byte[])` 方法：委托 `recognition.sendAudioFrame(ByteBuffer.wrap(pcm))`
- [x] 3.4 实现 `stop()` 方法：委托 `recognition.stop()`，释放资源
- [x] 3.5 添加 `AtomicBoolean` 防重入：未 start 时 sendFrame 抛异常，stop 后忽略 sendFrame
- [x] 3.6 注入 `dashscope.api-key` 配置

## 4. TTSService + SentenceSplitter

- [x] 4.1 创建 `com.voiceshopping.ai.tts.SentenceSplitter`，按中英文标点分句（`。！？；.!?\n`），空文本返回空列表
- [x] 4.2 创建 `com.voiceshopping.ai.tts.TTSService`，封装 DashScope `SpeechSynthesizer`(ttsv2)
- [x] 4.3 实现 `Flowable<byte[]> synthesize(String text)`：空文本直接返回 Flowable.empty()，非空则 SentenceSplitter 分句 → `streamingCallAsFlowable(Flowable.fromIterable(sentences))` → filter getAudioFrame 非空 → map 为 byte[]
- [x] 4.4 注入 `dashscope.api-key` 配置

## 5. WebSocket Handler + Config

- [x] 5.1 创建 `com.voiceshopping.web.websocket.WebSocketConfig`，实现 `WebSocketConfigurer`，注册 Handler 到 `/ws/voice`，调试阶段允许所有 origin
- [x] 5.2 创建 `com.voiceshopping.web.websocket.VoiceWebSocketHandler`，继承 `AbstractWebSocketHandler`
- [x] 5.3 实现 `afterConnectionEstablished`：创建 ASRService 实例，调用 `start()`，订阅 Flowable 处理识别结果
- [x] 5.4 实现 `handleBinaryMessage`：调用 `ASRService.sendFrame(payload)`
- [x] 5.5 实现 ASR 结果处理：`isSentenceEnd=false` 且 text 非空 → 发送 `AsrPartialResult` TextMessage；`isSentenceEnd=true` → 发送 `AsrFinalResult`，触发 TTS 流程
- [x] 5.6 实现 TTS 流程：调用 `TTSService.synthesize(text)`，订阅返回的 Flowable，每个 byte[] 帧通过 BinaryMessage 下发，完成后发送 `AgentStatus(status="done")`
- [x] 5.7 预留 Agent 接入点：在 ASR final → TTS 之间标注 `// TODO: Agent integration`，当前直接原文回显
- [x] 5.8 实现 `afterConnectionClosed`：调用 `ASRService.stop()`，日志记录
- [x] 5.9 所有 WebSocket 发送前判断 `session.isOpen()`，已关闭则 warn 级别日志跳过

## 6. voice-test.html 调试页

- [x] 6.1 创建 `src/main/resources/static/voice-test.html`
- [x] 6.2 实现状态机 `IDLE → CONNECTING → RECORDING → WAITING → CLOSING → IDLE`，按钮文案/颜色/可点击状态随状态切换
- [x] 6.3 实现 AudioWorklet 降采样：不指定 AudioContext sampleRate，Worklet 内部降采样到 16kHz
- [x] 6.4 设置 `noiseSuppression: false`
- [x] 6.5 实现 100ms 帧聚合（1600 samples @ 16kHz）
- [x] 6.6 实现 RMS 音量条：Worklet 每帧算 RMS postMessage 回主线程
- [x] 6.7 实现停止后补 800ms 静音帧（8 帧全零 PCM）
- [x] 6.8 实现 `userStopped` 标志：只在用户主动停止后的 ASR final 才关 WebSocket
- [x] 6.9 实现 session 闭包锁定当前连接，异步操作用闭包变量而非全局 ws
- [x] 6.10 实现 cleanup()：每次 start() 前显式 close/stop 旧的 AudioContext、WebSocket、MediaStream
- [x] 6.11 实现 playbackTime 维护：TTS 多帧 PCM 用 `startAt = max(now, lastEnd)` 无缝衔接播放（24kHz）

## 7. 编译验证

- [x] 7.1 执行 `mvn compile` 确认全项目编译通过
- [x] 7.2 执行 `mvn test` 确认已有测试不受影响

## 8. 人工验收

- [x] 8.1 启动 Spring Boot，浏览器访问 `http://localhost:8080/voice-test.html`，确认页面功能正常、F12 控制台无报错
