## ADDED Requirements

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
