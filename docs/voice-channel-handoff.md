# ASR 与 TTS 实时语音通道 — Handoff Summary

> 探索阶段结论，供 `/opsx:propose` 使用

## Problem

实现 ASR → Agent → TTS 的全链路实时语音通道，达成延迟目标：ASR 首包 < 300ms、TTS 首字音频 < 800ms、端到端首字 ≤ 1500ms。用户通过浏览器 WebSocket 与后端交互，完成语音输入识别、Agent 处理（当前阶段先做回显）、TTS 流式合成回播。

## Current State

- `voice-shopping-ai/pom.xml` 已引入 NLS SDK（`nls-sdk-common/transcriber/tts` 2.2.1）和 DashScope SDK（`dashscope-sdk-java` 2.22.4）
- `voice-shopping-web/pom.xml` 已引入 `spring-boot-starter-websocket`，但无任何 WebSocket Handler/Config 实现
- `voice-shopping-ai` 模块仅有 `AgentScopeConfig`（DashScope Chat Model 配置），无 ASR/TTS Service
- `voice-shopping-business` 模块为空壳（仅 pom.xml）
- `src/main/resources/static/` 目录不存在，无前端调试页
- 现有项目依赖方向：`web → business → ai → infrastructure → common`

## Decision

1. **ASR/TTS 放 `voice-shopping-ai` 模块**：与 DashScope SDK 同模块，依赖就近
2. **用 DashScope SDK 内置 API 替代 NLS SDK**：`Recognition` + `SpeechSynthesizer`(ttsv2) API 更现代，原生支持 Flowable 流式，无需手动桥接回调。移除 NLS SDK 三个依赖
3. **WebSocket 混合帧方案**：上行 BinaryMessage 传 PCM 音频帧，下行 BinaryMessage 传 TTS 音频帧 + TextMessage 传 JSON 信令（ASR 中间结果、状态变更、错误）
4. **TTS 按标点分句流式合成**：回显阶段就对完整文本按标点切分，用 `streamingCallAsFlowable(Flowable<String>)` 逐句提交、逐帧产出 PCM，架构与后续 LLM 流式输出阶段一致
5. **VAD 尾音静默 800ms**：停止录音后补 800ms 静音帧，确保 Paraformer 触发 `isSentenceEnd=true`
6. **voice-test.html**：单文件 H5 调试页，10 项设计要点（状态机、Worklet 降采样、音量条、playbackTime 衔播等）

### 整体数据流

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        Browser (voice-test.html)                         │
│                                                                          │
│  ┌──────────────┐     WebSocket /ws/voice          ┌─────────────┐      │
│  │ Mic → PCM    │ ──────── audio frames ──────────▶│             │      │
│  │ 16kHz 100ms  │                                  │   Server    │      │
│  │              │ ◀──── JSON + audio frames ───────│             │      │
│  │ ← Play PCM  │                                   └─────────────┘      │
│  │   24kHz      │                                                       │
│  └──────────────┘                                                        │
└──────────────────────────────────────────────────────────────────────────┘

                         Server 内部流
                    ┌───────────────────────┐
                    │ VoiceWebSocketHandler │
                    └──────────┬────────────┘
                               │ audio bytes (BinaryMessage)
                    ┌──────────▼────────────┐
                    │     ASRService        │  DashScope Recognition
                    │  start/sendFrame/stop │  Paraformer realtime-v1
                    └──────────┬────────────┘
                               │ isSentenceEnd text
                    ┌──────────▼────────────┐
                    │  OrchestratorAgent    │  ← TODO: 回显阶段直接原文返回
                    └──────────┬────────────┘
                               │ response text (按标点分句)
                    ┌──────────▼────────────┐
                    │     TTSService        │  DashScope SpeechSynthesizer
                    │  synthesize(text)     │  CosyVoice ttsv2
                    └──────────┬────────────┘
                               │ PCM frames (24kHz)
                    ┌──────────▼────────────┐
                    │  WebSocket sendBinary │
                    └───────────────────────┘
```

### ASRService API 对接

```
对外接口:
  Flowable<RecognitionResult> start()     ← 建连，返回结果流
  void sendFrame(byte[] pcm)              ← WebSocket 上行音频帧
  void stop()                             ← 通知 ASR 结束

内部实现:
  Recognition.call(param, callback)       ← DashScope SDK callback 模式
  callback.onEvent → PublishProcessor 下发结果
  sendFrame → recognition.sendAudioFrame(ByteBuffer.wrap(pcm))
  stop     → recognition.stop()
```

### TTSService API 对接

```
对外接口:
  Flowable<byte[]> synthesize(String text)  ← 按标点分句，逐句流式合成

内部实现:
  List<String> sentences = SentenceSplitter.split(text)
  Flowable<String> textStream = Flowable.fromIterable(sentences)
  synthesizer.streamingCallAsFlowable(textStream)
      .filter(r -> r.getAudioFrame() != null)
      .map(r -> r.getAudioFrame().array())
```

## Scope

### 后端

| 组件 | 模块 | 说明 |
|------|------|------|
| `ASRService` | ai | 封装 `Recognition`，暴露 `start()/sendFrame()/stop()` + `Flowable<RecognitionResult>` |
| `TTSService` | ai | 封装 `SpeechSynthesizer`(ttsv2)，暴露 `Flowable<byte[]> synthesize(String text)` |
| `SentenceSplitter` | ai | 按标点分句工具，供 TTSService 使用 |
| `VoiceWebSocketHandler` | web | 处理 WebSocket 连接生命周期，编排 ASR→Agent→TTS 链路 |
| `WebSocketConfig` | web | 注册 Handler 到 `/ws/voice` |
| WebSocket 消息 DTO | common | 下行 JSON 信令的 Record 定义 |
| pom.xml 变更 | ai | 移除 NLS SDK 依赖 |

### 前端

| 文件 | 说明 |
|------|------|
| `src/main/resources/static/voice-test.html` | 单文件 H5 调试页 |

## Out of Scope

- Agent 真实逻辑（意图识别、推荐、下单）—— 本阶段 `VoiceWebSocketHandler` 只做回显（ASR 文本 → 原文 TTS 回播），打 TODO 标记
- 认证鉴权（Sa-Token 拦截 WebSocket）—— 后续版本接入
- 生产前端工程 —— 本阶段只提供调试页
- 并发会话管理 / 多用户隔离 —— 单用户调试场景
- TTS 音色选择 / SSML —— 使用默认 CosyVoice 音色
- 录音暂停/恢复 —— 只做开始/停止

## Requirements

### FR-1 ASRService

- 基于 `com.alibaba.dashscope.audio.asr.recognition.Recognition`（DashScope SDK）
- 对外暴露三个方法：`start()` 返回 `Flowable<RecognitionResult>`、`sendFrame(byte[])` 推送音频帧、`stop()` 结束识别
- 参数：`paraformer-realtime-v1`、`pcm`、`sampleRate=16000`
- `RecognitionResult.isSentenceEnd()` 为 true 时，文本作为完整句子提交给下游

### FR-2 TTSService

- 基于 `com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer`（DashScope SDK ttsv2）
- 对外暴露 `Flowable<byte[]> synthesize(String text)`
- 内部按标点分句，用 `streamingCallAsFlowable(Flowable<String>)` 逐句流式合成
- 输出 PCM 音频帧（24kHz, 16bit, mono）
- `ResultCallback` 不传 null（构造函数要求非 null 或使用 Flowable API）

### FR-3 SentenceSplitter

- 按中英文标点（`。！？；.!?\n`）分句
- 空文本或纯空白返回空列表
- 保留标点在句末

### FR-4 VoiceWebSocketHandler

- 继承 `AbstractWebSocketHandler`（Spring WebSocket）
- 上行 `handleBinaryMessage`：接收 PCM 帧，推送给 ASRService.sendFrame()
- ASR `isSentenceEnd=true` 时，获取完整文本，触发 Agent 流程（当前 TODO: 直接回显原文）
- 回显文本送入 TTSService.synthesize()，返回的 PCM 帧通过 BinaryMessage 下发
- 下行 TextMessage：JSON 格式推送 ASR 中间结果、状态变更、错误信息
- 连接关闭时清理 ASR/TTS 资源
- 发送前判断 `session.isOpen()`，日志级别 warn 而非 error

### FR-5 WebSocketConfig

- 实现 `WebSocketConfigurer`
- 注册 `VoiceWebSocketHandler` 到路径 `/ws/voice`
- 允许所有 origin（调试阶段）

### FR-6 下行 JSON 信令 DTO

定义在 `com.voiceshopping.common.dto`，使用 Record：

- `AsrPartialResult(String type, String text)` — ASR 增量文字（type="asr_partial"）
- `AsrFinalResult(String type, String text)` — ASR 句结束文字（type="asr_final"）
- `AgentStatus(String type, String status)` — Agent 状态（type="agent_status", status="processing"/"responding"）
- `VoiceError(String type, String code, String message)` — 错误（type="error"）

### FR-7 voice-test.html

单文件 H5 调试页，满足以下 10 项设计要求：

1. **状态机** `IDLE → CONNECTING → RECORDING → WAITING → CLOSING → IDLE`，每个阶段按钮文案/颜色/可点击状态自动切换
2. **AudioContext 不指定 sampleRate**，AudioWorklet 内部降采样到 16kHz（兼容 Safari/旧 Chromium）
3. **noiseSuppression: false**，防止 Chrome 削弱小音量
4. **100ms 聚合一帧**（1600 samples @ 16kHz），避免 WebSocket 消息风暴
5. **实时音量条**，Worklet 每帧算 RMS 发回主线程
6. **停止后补 800ms 静音帧**，确保 Paraformer VAD 触发句结束
7. **userStopped 标志**，只在用户主动停止之后的 `isSentenceEnd=true` 才关 WebSocket
8. **session 闭包锁定当前连接**，setTimeout 等异步操作用闭包变量而非全局 ws
9. **每次 start() 前 cleanup()**，显式 close/stop 上一次的 AudioContext、WebSocket、MediaStream
10. **playbackTime 维护**，TTS 多帧 PCM 累计 `startAt = max(now, lastEnd)` 无缝衔接播放

### FR-8 pom.xml 清理

- `voice-shopping-ai/pom.xml`：移除 `nls-sdk-common`、`nls-sdk-transcriber`、`nls-sdk-tts` 三个依赖

## Acceptance Criteria

| # | 条件 | 验证方式 |
|---|------|---------|
| AC-1 | 启动 Spring Boot，浏览器访问 `http://localhost:8080/voice-test.html` 可加载调试页 | 手动访问 |
| AC-2 | 点击"开始录音"，麦克风权限弹窗出现（localhost 或 HTTPS），音量条响应说话声 | 手动测试 |
| AC-3 | 说话后，页面实时显示 ASR 增量文字（Live Caption） | 观察页面 |
| AC-4 | 点击"停止"后，页面收到 ASR 最终文字，随后播放 TTS 回读音频 | 听到语音回播 |
| AC-5 | ASR 首包延迟 < 300ms（后端日志 `firstPackageDelay`） | 查看日志 |
| AC-6 | TTS 首字音频延迟 < 800ms（后端日志 `firstPackageDelay`） | 查看日志 |
| AC-7 | 多次开始/停止循环，无资源泄漏（WebSocket/AudioContext/MediaStream 正常释放） | Chrome DevTools → Network/Connections |
| AC-8 | 后端日志无 `ResultCallback is null` 或 `IllegalStateException: session has been closed`（warn 级别可接受） | 查看日志 |
| AC-9 | `mvn compile` 通过，无编译错误 | 命令行 |
| AC-10 | 移除 NLS SDK 依赖后编译和运行正常 | 命令行 |

## Impacted Files / Modules

```
voice-shopping-ai/
  pom.xml                                          ← 移除 NLS SDK 依赖
  src/main/java/.../ai/
    asr/
      ASRService.java                               ← 新增
    tts/
      TTSService.java                               ← 新增
      SentenceSplitter.java                          ← 新增

voice-shopping-common/
  src/main/java/.../common/dto/
    AsrPartialResult.java                           ← 新增 (Record)
    AsrFinalResult.java                             ← 新增 (Record)
    AgentStatus.java                                ← 新增 (Record)
    VoiceError.java                                 ← 新增 (Record)

voice-shopping-web/
  src/main/java/.../web/
    websocket/
      VoiceWebSocketHandler.java                    ← 新增
      WebSocketConfig.java                          ← 新增
  src/main/resources/
    static/
      voice-test.html                               ← 新增

voice-shopping-business/                            ← 不变（空壳）
voice-shopping-infrastructure/                      ← 不变
```

## Risks

| # | 风险 | 概率 | 影响 | 缓解 |
|---|------|------|------|------|
| R-1 | DashScope SDK 版本 2.22.4 中 `Recognition`/`SpeechSynthesizer`(ttsv2) API 与本文档描述不一致 | 低 | 高 | 实现阶段先跑 SDK 单元测试验证 API |
| R-2 | CosyVoice 输出采样率不是 24kHz（可能 22050Hz 等其他值） | 低 | 中 | TTS 首帧到达后打印 format 信息确认；前端按后端实际告知的采样率播放 |
| R-3 | Paraformer `NO_VALID_AUDIO_ERROR` 在环境安静时频繁触发 | 中 | 低 | 前端音量条给用户实时反馈；voice-test.html 已有排查指引 |
| R-4 | `SpeechSynthesizer` 构造函数在 callback=null 时抛异常 | 低 | 低 | 使用 `streamingCallAsFlowable()` Flowable API，无需 callback |
| R-5 | 浏览器 AudioContext 上限（Chrome 约 6 个）导致资源泄漏 | 低 | 中 | FR-7 第 9 项每次 start() 前 cleanup() |

## Open Questions

（无）—— 所有关键决策已在 explore 阶段确认。
