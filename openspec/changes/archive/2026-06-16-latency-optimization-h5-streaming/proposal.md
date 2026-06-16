## Why

当前语音购物全链路（ASR→Intent→Clarify→Recommend→ReasonLLM→EmotionLLM→TTS）端到端延迟约 2.9s，远超 1.5s 语音交互生命线。主要瓶颈：推荐理由（RecommendReasonService）和情感应答（EmotionService）是两次串行 LLM 调用，合计 ~1.4s；且整个 EmotionAgent 输出完成后才送 TTS，首帧音频到达更晚。

## What Changes

- **合并 ReasonLLM + EmotionLLM 为一次调用**：将 RecommendReasonService 从主链路摘除，EmotionAgent 直接接收商品裸数据 + userNeeds，一步生成口语化推荐理由 + 情感包装。省 ~600ms。
- **EmotionAgent 流式输出**：基于 `ReActAgent.stream()` 返回字级 Flux，经 SentenceAggregator 聚合为可合成单元，首句 ~200ms 即可送 TTS。
- **OrchestratorService 新增 `streamHandle()`**：返回 `Flux<StreamChunk>`，产品卡片、文字流、音频流三帧分时推送。
- **WebSocket 协议扩展**：新增 `stream_text` / `stream_products` / `stream_done` JSON 信号类型，TTS PCM 二进制通道不变。
- **H5 前端改造**：voice-test.html 接收三帧，产品卡片区域 + 字幕区域 + 扬声器同步。
- **合规检查流式化**：SentenceAggregator 聚出句子后逐句过合规，再发前端。
- **RecommendReasonService 保留但不调用**：作为回滚资产，主链路彻底走新 prompt。

## Capabilities

### New Capabilities
- `emotion-streaming`: EmotionAgent 流式输出 + SentenceAggregator 分句聚合 + EmotionStreamingService 封装
- `stream-chunk-protocol`: StreamChunk record + WebSocket 三帧协议（TEXT/PRODUCTS/AUDIO）+ OrchestratorService.streamHandle() 流式编排

### Modified Capabilities
- `emotion-service`: 输入新增 userNeeds，去掉 explanationTone；products 传裸数据（含 attributes）；prompt 换为 emotion-merged.txt；输出解析从 JSON speechText 改为纯文本 + markdown 包裹兜底
- `rec-orchestration`: RecommendOrchestrator 和 ParallelRecommendService 不再调用 RecommendReasonService，recommend() 直接返回 reason=null 的 topK
- `orchestrator-service`: 新增 streamHandle() 方法，finalizeTurn 拆为流式版（合规逐句检 + 记忆/状态后置写入）
- `voice-websocket`: handleAsrResult 从同步 orchestratorService.handle() 改为订阅 Flux<StreamChunk>
- `voice-test-page`: 接收三帧协议，新增产品卡片区域 + 字幕打字机效果 + 音频同步播放
- `emotion-agent`: prompt 文件从 emotion.txt 切换为 emotion-merged.txt

## Impact

- **代码变更**：`voice-shopping-ai`（EmotionAgentBuilder、+SentenceAggregator）、`voice-shopping-business`（EmotionService、+EmotionStreamingService、OrchestratorService、RecommendOrchestrator、ParallelRecommendService）、`voice-shopping-web`（VoiceWebSocketHandler、EmotionDebugReq、voice-test.html）、`voice-shopping-common`（+StreamChunk record）
- **API 变更**：EmotionDebugReq 新增 userNeeds 字段；WebSocket 新增 stream_* 信号类型（向后兼容，旧信号 asr_partial/asr_final/agent_status/error 保留）
- **依赖**：无新外部依赖，Reactor/Flux 已在 classpath
- **回滚方案**：emotion.txt + RecommendReasonService 类文件保留，可通过切换 prompt 文件名 + 恢复 @Autowired 回滚
