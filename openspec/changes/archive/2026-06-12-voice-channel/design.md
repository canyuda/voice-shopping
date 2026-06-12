## Context

项目已有完整的文本 API 层（商品搜索、FAQ 问答、用户画像、会话管理），依赖方向 `web → business → ai → infrastructure → common`。`voice-shopping-ai` 模块引入了 DashScope SDK 2.22.4（含 Recognition + SpeechSynthesizer）和独立的 NLS SDK 2.2.1。`voice-shopping-web` 已引入 `spring-boot-starter-websocket` 但无 Handler 实现。

当前阶段无任何语音处理能力，需要建立 WebSocket 全双工通道和 ASR/TTS 流式管道。Agent 逻辑暂不实现，先用回显模式验证整条链路。

## Goals / Non-Goals

**Goals:**
- 建立 WebSocket 全双工语音通道（上行 PCM 音频，下行 PCM 音频 + JSON 信令）
- 实现 ASR 流式识别（Paraformer realtime-v1），延迟 < 300ms
- 实现 TTS 按标点分句流式合成（CosyVoice ttsv2），延迟 < 800ms
- 提供可用的 H5 调试页，验证端到端链路
- 架构预留 Agent 接入点，回显模式可无缝替换为真实 Agent

**Non-Goals:**
- Agent 意图识别/推荐/下单逻辑
- Sa-Token WebSocket 鉴权
- 并发多用户会话管理
- 生产前端工程
- TTS 音色选择 / SSML

## Decisions

### D-1: 使用 DashScope SDK 内置 API，移除 NLS SDK

**选择**: `com.alibaba.dashscope.audio.asr.recognition.Recognition` + `com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer`

**替代方案**: NLS SDK（`nls-sdk-transcriber` + `nls-sdk-tts`）— 更底层，需手动管 WebSocket 生命周期，TTS 只有回调模式需 Flowable.create 桥接。

**理由**: DashScope SDK 原生 Flowable API（`streamCall` / `streamingCallAsFlowable`），与项目 RxJava 管道无缝衔接；ASR callback 模式天然匹配 WebSocket 帧推送；减少一个外部 SDK 的维护负担。

### D-2: ASR/TTS Service 放 voice-shopping-ai 模块

**选择**: `com.voiceshopping.ai.asr.ASRService` + `com.voiceshopping.ai.tts.TTSService`

**替代方案**: 放 `voice-shopping-infrastructure`（与 EmbeddingService 同层）。

**理由**: NLS/DashScope SDK 依赖在 ai 模块 pom 中；ASR/TTS 与 AI 模型调用关系更紧密；infrastructure 层定位是数据存取（JPA/Redis/pgvector）。

### D-3: WebSocket 混合帧协议

**选择**: BinaryMessage 传 PCM 音频帧，TextMessage 传 JSON 信令。

**替代方案**: (A) 自定义二进制协议（首字节 type tag）；(B) 双 WebSocket 连接（音频+信令）。

**理由**: Spring WebSocketHandler 原生区分 `handleBinaryMessage` / `handleTextMessage`；浏览器 WebSocket API 也原生支持；实现最简单，调试最直观。

### D-4: TTS 按标点分句，用 streamingCallAsFlowable

**选择**: `SentenceSplitter` 按标点切分 → `Flowable.fromIterable(sentences)` → `synthesizer.streamingCallAsFlowable(textStream)`。

**替代方案**: (A) 整段一次性 `callAsFlowable(text)`；(B) 每句独立 `callAsFlowable(sentence)` 多次连接。

**理由**: 分句流式降低首字延迟；`streamingCallAsFlowable` 一个 WebSocket 连接内多句提交，后续句子无建连开销；回显阶段和 LLM 流式输出阶段代码一致，只需换文字来源。

### D-5: ASRService 用 callback 模式 + PublishProcessor

**选择**: `Recognition.call(param, callback)` + `sendAudioFrame()` + `stop()`，callback 通过 `PublishProcessor<RecognitionResult>` 下发结果。

**替代方案**: `Recognition.streamCall(param, audioFlow)` 纯 Flowable 模式。

**理由**: WebSocket Handler 需要在收到浏览器音频帧时主动推送，callback 模式（start/sendFrame/stop）与 WebSocket 生命周期对齐。纯 Flowable 模式需提前构造完整音频 Flowable，不适合 WebSocket 帧逐步到达的场景。

### D-6: voice-test.html 十项设计要点

状态机驱动、AudioWorklet 降采样、noiseSuppression: false、100ms 帧聚合、RMS 音量条、800ms 静音补帧、userStopped 关闭标志、session 闭包、cleanup 资源释放、playbackTime 无缝播放。

**理由**: 均为社区踩坑总结的必要实践，缺少任一项都会导致调试困难或资源泄漏。

### D-7: 下行信令 DTO 使用 Record

**选择**: 4 个 Record 类型 — `AsrPartialResult`、`AsrFinalResult`、`AgentStatus`、`VoiceError`

**理由**: 遵循 CLAUDE.md 规范"接口请求体和响应体禁止使用 Map<String, Object>"；Record 不可变、自带 equals/hashCode/toString；Jackson 原生支持。

## Risks / Trade-offs

- **[R-1] DashScope SDK API 与文档不一致** → 实现阶段先写集成测试验证 Recognition 和 SpeechSynthesizer 的实际行为
- **[R-2] CosyVoice 输出采样率不确定** → TTS Service 首帧打印 format 信息，前端按实际采样率播放
- **[R-3] Paraformer NO_VALID_AUDIO_ERROR** → 前端音量条实时反馈，voice-test.html 内嵌排查指引
- **[R-4] callback 模式下 Recognition 状态管理** → ASRService 内部用 AtomicBoolean 防重入，stop() 后忽略 sendFrame
- **[R-5] 浏览器 AudioContext 上限** → voice-test.html 每次 start() 前 cleanup() 释放旧资源
