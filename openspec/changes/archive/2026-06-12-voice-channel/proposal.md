## Why

项目需要实时语音通道来支撑语音购物助手的核心交互链路：用户说话 → ASR 识别 → Agent 处理 → TTS 回播。当前后端仅有文本 API（搜索、FAQ、画像），无任何语音处理能力。本阶段先建立 WebSocket 全双工通道和 ASR/TTS 流式管道（回显模式），为后续 Agent 接入打好基础。

## What Changes

- 新增 `ASRService`：封装 DashScope SDK `Recognition`，支持流式音频帧推送和实时识别结果回调
- 新增 `TTSService`：封装 DashScope SDK `SpeechSynthesizer`(ttsv2 CosyVoice)，支持按标点分句流式合成
- 新增 `SentenceSplitter`：中英文标点分句工具
- 新增 `VoiceWebSocketHandler`：WebSocket 全双工处理器，编排 ASR→回显→TTS 链路（Agent 接入点预留 TODO）
- 新增 `WebSocketConfig`：注册 Handler 到 `/ws/voice`
- 新增 4 个下行 JSON 信令 DTO（Record）：`AsrPartialResult`、`AsrFinalResult`、`AgentStatus`、`VoiceError`
- 新增 `voice-test.html`：单文件 H5 调试页，支持录音/播放/音量条/状态机管理
- **移除** NLS SDK 依赖（`nls-sdk-common`、`nls-sdk-transcriber`、`nls-sdk-tts`），改用 DashScope SDK 内置 ASR/TTS API

## Capabilities

### New Capabilities

- `asr-service`: 流式语音识别服务，封装 DashScope Recognition SDK，暴露 start/sendFrame/stop 接口
- `tts-service`: 流式语音合成服务，封装 DashScope SpeechSynthesizer(ttsv2) SDK，支持按标点分句流式合成
- `voice-websocket`: WebSocket 全双工语音通道入口，混合帧协议（Binary=音频, Text=JSON 信令），编排 ASR→Agent→TTS 链路
- `voice-test-page`: H5 调试页，状态机驱动的录音/TTS播放/音量显示/资源管理

### Modified Capabilities

- `dependency-management`: 移除 NLS SDK 三个依赖，ASR/TTS 统一走 DashScope SDK

## Impact

- **模块**: `voice-shopping-ai`（新增 ASR/TTS Service，移除 NLS SDK）、`voice-shopping-common`（新增 DTO）、`voice-shopping-web`（新增 WebSocket Handler/Config、静态资源）
- **依赖**: 移除 `nls-sdk-common`/`nls-sdk-transcriber`/`nls-sdk-tts`，无新增外部依赖（DashScope SDK 已在项目中）
- **API**: 新增 WebSocket 端点 `ws://host:8080/ws/voice`，不影响现有 HTTP API
- **配置**: 可能需要在 `application.yml` 新增 TTS 音色等配置项（本次先用默认值）
