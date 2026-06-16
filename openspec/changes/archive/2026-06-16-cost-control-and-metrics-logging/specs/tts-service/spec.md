## ADDED Requirements

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
