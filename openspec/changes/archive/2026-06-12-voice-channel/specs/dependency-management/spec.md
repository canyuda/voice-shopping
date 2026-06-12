## REMOVED Requirements

### Requirement: NLS SDK 依赖
**Reason**: ASR/TTS 统一使用 DashScope SDK 内置的 `Recognition` + `SpeechSynthesizer`(ttsv2) API，NLS SDK 不再需要。
**Migration**: 从 `voice-shopping-ai/pom.xml` 移除 `nls-sdk-common`、`nls-sdk-transcriber`、`nls-sdk-tts` 三个依赖。所有 ASR/TTS 功能通过 `dashscope-sdk-java` 的 `com.alibaba.dashscope.audio.asr.recognition` 和 `com.alibaba.dashscope.audio.ttsv2` 包提供。
