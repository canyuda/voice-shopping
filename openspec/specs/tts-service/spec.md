## Purpose

TTSService 流式语音合成能力，封装 DashScope 语音合成、分句、流式 PCM 输出与成本埋点。

## Requirements

### Requirement: TTSService 流式语音合成
TTSService SHALL 封装 DashScope SDK `SpeechSynthesizer`(ttsv2)，对外暴露 `Flowable<byte[]> synthesize(String text)`。内部按标点分句后使用 `streamingCallAsFlowable(Flowable<String>)` 逐句流式合成。

#### Scenario: 单句合成
- **WHEN** 调用 `synthesize("你好")`
- **THEN** 返回的 Flowable 发射一个或多个 24kHz 16bit mono PCM 音频帧（byte[]）
- **THEN** Flowable 最终发射 onComplete

#### Scenario: 多句分句合成
- **WHEN** 调用 `synthesize("你好。我想买个手表。")`
- **THEN** 内部按标点分为 ["你好。", "我想买个手表。"]
- **THEN** 两句在同一条 WebSocket 连接内依次合成
- **THEN** Flowable 持续发射音频帧，无需等待全部句子合成完毕

#### Scenario: 空文本处理
- **WHEN** 调用 `synthesize("")` 或 `synthesize(null)`
- **THEN** 返回空的 Flowable（直接 onComplete）

#### Scenario: 合成异常处理
- **WHEN** DashScope SDK 合成过程出错
- **THEN** Flowable 发射 onError，包含原始异常信息

### Requirement: SentenceSplitter 标点分句
SentenceSplitter SHALL 按中英文标点（`。！？；.!?\n`）分句，标点保留在句末。空文本或纯空白 SHALL 返回空列表。

#### Scenario: 中文标点分句
- **WHEN** 输入 "你好。我想买个手表！多少钱？"
- **THEN** 返回 ["你好。", "我想买个手表！", "多少钱？"]

#### Scenario: 英文标点分句
- **WHEN** 输入 "Hello! How are you? Fine."
- **THEN** 返回 ["Hello!", " How are you?", " Fine."]

#### Scenario: 无标点文本
- **WHEN** 输入 "你好"
- **THEN** 返回 ["你好"]

#### Scenario: 空文本
- **WHEN** 输入 "" 或 "   "
- **THEN** 返回空列表 []

### Requirement: TTSService 调用埋点

`TTSService.synthesize(String text)` 和 `TTSService.streamSynthesize(Publisher<String>)` SHALL 在调用完成时通过 `CostMetricsLogger.logTts` 输出成本日志。

字段约束：
- `scene`: `TTS`
- `sessionId` / `userId`: 由调用方透传（同 EmbeddingService，实现策略二选一：扩展方法签名 / 上层包裹埋点）
- `model`: 当前 TTS 模型名（如 `cosyvoice-v1`）
- `inputChars`: 喂给 DashScope 的全部文本字符数总和
  - synthesize: 入参 text 字符数
  - streamSynthesize: 累加每个句子的字符数，在流 complete 时输出
- `durationMs`: 从 streamingCallAsFlowable 调用到 onComplete 的总耗时

#### Scenario: 同步 synthesize 埋点
- **WHEN** TTSService.synthesize("好，给你挑了几款。第一款是...") 完成
- **THEN** cost-metrics.log 出现 `scene=TTS sessionId=<id> userId=<id> model=cosyvoice-v1 inputChars=<n> durationMs=<m>`

#### Scenario: 流式 streamSynthesize 累计埋点
- **WHEN** TTSService.streamSynthesize 收到 5 个句子（共 87 字符），完成时
- **THEN** cost-metrics.log 出现 1 条 `scene=TTS inputChars=87 ...`，不是 5 条

#### Scenario: 单次 TTS 调用 1 条日志
- **WHEN** 一轮对话触发 1 次 TTS 流式合成
- **THEN** cost-metrics.log 仅出现 1 条 TTS 埋点
- **THEN** 与同轮的 1 条 ASR 埋点 + 多条 LLM 埋点共同构成完整的"一轮成本快照"
