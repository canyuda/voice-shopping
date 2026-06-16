## 1. Prompt 与 DTO 基础设施

- [x] 1.1 创建 `prompts/emotion-merged.txt`（合并推荐理由+情感包装的新 prompt，含 userNeeds/products 输入、纯文本输出约束）
- [x] 1.2 创建 `StreamChunk` record（`com.voiceshopping.common.dto.agent.StreamChunk`，含 Type 枚举 + text/audio/products 静态工厂方法）
- [x] 1.3 创建 `StreamText`/`StreamProducts`/`StreamDone` 下行信令 DTO（`com.voiceshopping.common.dto` 包下）
- [x] 1.4 修改 `EmotionDebugReq` record，新增 `userNeeds` 字段（可选，默认空字符串）

## 2. 推荐管道去 ReasonService

- [x] 2.1 修改 `RecommendOrchestrator`：构造器移除 `RecommendReasonService` 参数，`recommend()` 中不再调用 `reasonService.attachReasons`，直接返回 reason=null 的 topK
- [x] 2.2 修改 `ParallelRecommendService`：构造器移除 `RecommendReasonService` 参数，`recommend()` 中不再调用 `reasonService.attachReasons`
- [x] 2.3 修改 `EmotionAgentBuilder`：`PROMPT_FILE` 常量从 `"emotion.txt"` 改为 `"emotion-merged.txt"`

## 3. EmotionService 改造

- [x] 3.1 修改 `EmotionService.wrap()`：新增 `userNeeds` 参数，构造 userMsg 时去掉 `explanationTone` 字段，加入 `userNeeds` 字段，products 传裸数据（含 name/price/attributes，不含 matchScore/reason）
- [x] 3.2 修改 `EmotionPromptInput` record：去掉 `explanationTone`，新增 `userNeeds`，`LeanProduct` 扩展为含 `productId`/`name`/`price`/`attributes`
- [x] 3.3 重写 `parseSpeech()`：直接取 rawText，strip `\`\`\`json ... \`\`\`` 包裹 + 去换行符，不再依赖 `IntentService.extractJson` + JSON 解析
- [x] 3.4 修改 `EmotionDebugController`：传入 `req.userNeeds()` 给 `emotionService.wrap()`

## 4. OrchestratorService 三处调用改签名

- [x] 4.1 `runRecommendation()`：EmotionService.wrap 调用新增 userNeeds 参数（从 mergedSlots 转换：`key1=val1,key2=val2,...`）
- [x] 4.2 `runCompare()`：EmotionService.wrap 调用新增 userNeeds 参数（从 compareSlots 转换）
- [x] 4.3 `runChitchat()`：EmotionService.wrap 调用新增 userNeeds 参数（传空字符串 `""`）

## 5. 流式基础设施

- [x] 5.1 创建 `SentenceAggregator`（`com.voiceshopping.ai.tts`）：实现 `Flux<String> aggregate(Flux<String> charFlux, Duration candidateTimeout)`，确定断点(`。！？`)立即 flush，候选断点(`，、；`)50ms 超时 flush，流结束 flush 剩余
- [x] 5.2 添加配置项 `voice-shopping.streaming.sentence-aggregate-timeout-ms`（默认 50）
- [x] 5.3 创建 `EmotionStreamingService`（`com.voiceshopping.business.agent`）：实现 `Flux<String> streamWrap(sessionId, userUtterance, userNeeds, rec)`，基于 `agent.stream()` 返回字级 Flux，异常时返回 fallback 单元素流

## 6. OrchestratorService.streamHandle 流式编排

- [x] 6.1 `OrchestratorService` 构造器新增 `EmotionStreamingService` 和 `TTSService` 依赖注入
- [x] 6.2 实现 `streamHandle()` 方法：前置逻辑与 handle() 相同，dispatch 后改为流式——推荐完成后 `Flux.just(StreamChunk.products(rec.items()))` 先发，再接 EmotionStreamingService → SentenceAggregator → 逐句过合规 → emit TEXT + 送 TTS emit AUDIO
- [x] 6.3 实现 `doFinally` 后置逻辑：收集完整回复文本，写 ASSISTANT turn + TURN summary + 持久化 session_state；异常路径写 fallback 记忆
- [x] 6.4 合规逐句检查：SentenceAggregator 输出的每个句子在 emit TEXT 前过 `ComplianceChecker.ensureCompliant()`，违规句子替换为安全占位文本

## 7. WebSocket 流式接入

- [x] 7.1 修改 `VoiceWebSocketHandler.handleAsrResult()`：ASR 句结束后订阅 `orchestratorService.streamHandle()` 返回的 Flux，按 StreamChunk.Type 分发：PRODUCTS→StreamProducts JSON、TEXT→StreamText JSON、AUDIO→BinaryMessage PCM
- [x] 7.2 流完成时发送 `StreamDone` 信令，流异常时发送 `VoiceError` + fallback TTS

## 8. H5 前端改造

- [x] 8.1 `voice-test.html` 新增产品卡片区域（`#product-cards`），接收 `stream_products` 信令后渲染商品卡片（name + price + attributes 标签）
- [x] 8.2 `voice-test.html` 新增字幕区域（`#caption`），接收 `stream_text` 信令后逐段追加显示（打字机效果），新一轮录音时清空
- [x] 8.3 `voice-test.html` onmessage switch 新增 `stream_text`/`stream_products`/`stream_done` 处理分支，WAITING 状态等待 `stream_done` 而非 `agent_status=done`
- [x] 8.4 保留现有 `agent_status` / `asr_partial` / `asr_final` / `error` 信令处理兼容

## 9. 测试与验证

- [x] 9.1 编译验证：`mvn compile` 通过，无编译错误
- [x] 9.2 现有单元测试通过：`OrchestratorServiceTest` / `OrchestratorOrderConfirmTest` / `ParallelRecommendEquivalenceTest` 适配新签名后全部绿
- [x] 9.3 端到端验证：需启动应用（依赖 DashScope API Key + PG + Redis），本地手动验证产品卡片 + 字幕 + 音频三帧
