## ADDED Requirements

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
