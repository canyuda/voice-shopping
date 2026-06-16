## Context

语音购物全链路当前延迟约 2.9s，超过 1.5s 语音交互生命线。瓶颈在两处：

1. **两次串行 LLM**：RecommendReasonService（~600ms）+ EmotionService（~800ms）合计 1.4s
2. **全量生成后送 TTS**：EmotionAgent 必须完整输出后才开始 TTS 合成，首帧音频额外等待 ~500ms

当前架构：`VoiceWebSocketHandler.handleAsrResult()` 同步调用 `orchestratorService.handle()` → 返回 `EmotionResult` → 串行发送 displayBlocks → `speak()` 发起 TTS。整个流程是阻塞式的。

现有 AgentScope Java 的 `ReActAgent.stream(msg)` 返回字级 `Flux<Event>`，模型吐第一个字就能往下走，这是流式化的基础设施。

## Goals / Non-Goals

**Goals:**
- 合并 ReasonLLM + EmotionLLM 为一次调用，省掉 ~600ms 串行 LLM 延迟
- EmotionAgent 流式输出，首句 ~200ms 即可送 TTS，体感延迟压至 ~1.5s 以内
- WebSocket 推送三帧（产品/文字/音频），前端实时渲染产品卡片 + 字幕 + 扬声器
- 合规检查流式化（逐句检查），不阻塞首句输出
- 保持回滚能力（旧 prompt + ReasonService 类文件保留）

**Non-Goals:**
- 不优化 ASR / Intent / Clarify 阶段延迟（属于独立优化方向）
- 不改造移动端原生 SDK（本次只改 H5 调试页）
- 不做 TTS 首帧优化（当前 TTS 已是流式，首帧 ~100ms）
- 不改动 RecAgent / ClarifyAgent / IntentAgent 的流式行为

## Decisions

### D1: 合并 ReasonLLM + EmotionLLM → EmotionAgent 一次调用

**选择**：将 RecommendReasonService 从主链路摘除，EmotionAgent 通过新 prompt `emotion-merged.txt` 直接接收商品裸数据 + userNeeds，一步生成推荐理由 + 情感包装。

**备选方案**：
- A: 两次 LLM 并行调用（ReasonLLM 和 EmotionLLM 同时发）→ 放弃，因为 EmotionLLM 依赖 ReasonLLM 的输出（reason 字段），无法真正并行
- B: 保持两次但用 RecAgent 的流式输出来串联 → 放弃，架构复杂度更高但延迟收益不如合并

**理由**：合并后 prompt 输入更丰富（含 attributes），模型能基于真实属性生成更精准的理由；输出从 JSON `{"speechText":"..."}` 简化为纯文本，解析更鲁棒。

### D2: SentenceAggregator 逗号断句策略 — C 方案

**选择**：逗号作为"候选断点"，遇到逗号后等待 50ms，若无新字到来则切分送 TTS；句号/感叹号/问号为"确定断点"，立即切分。

**备选方案**：
- A: 只遇句号才送 → 延迟高但语音连贯
- B: 遇逗号也送 → 延迟低但可能断句生硬

**理由**：C 方案折中，逗号处短暂等待可避免将"别纠结，给你挑了三款"切成两个不自然片段，同时不会像 A 方案那样等太久。50ms 阈值可配置化。

实现方式：`Flux<String>` 字级流 → `bufferTimeout(1, Duration.ofMillis(50))` + 自定义 operator 判断标点类型。确定断点（`。！？`）直接 flush；候选断点（`，、；`）启动 50ms 定时器。

### D3: 产品列表用 Flux.concat 保证先于文字

**选择**：`Flux.concat(Flux.just(StreamChunk.products(rec.items())), textAudioFlux)` 确保产品卡片在文字流之前到达前端。

**理由**：`Flux.merge()` 不保序，产品可能晚于文字到达，导致前端先出字幕再出卡片，体验割裂。`concat` 保证严格顺序。

### D4: 合规检查流式化 — C 方案（逐句检查）

**选择**：SentenceAggregator 聚出每个可合成单元后，先过 ComplianceChecker 再发送 TEXT + 送 TTS。

**理由**：当前 `ComplianceChecker.ensureCompliant` 是占位符，未来上线后需要拦截违规内容。逐句检查使首句合规后即可发出，不阻塞后续句子。如果某句违规，替换为安全占位文本后继续。

### D5: WebSocket 协议 — 二进制通道不变，TEXT/PRODUCTS 走 JSON

**选择**：
- TTS PCM 保持现有二进制 Blob 通道不变
- 新增 JSON 信号类型：`stream_text`（字幕片段）、`stream_products`（产品卡片）、`stream_done`（流结束）
- 旧信号（`asr_partial`/`asr_final`/`agent_status`/`error`）保留兼容

**备选方案**：
- A: 所有帧统一走 JSON + base64 编码音频 → 放弃，base64 编解码开销约 33%，且破坏现有二进制播放逻辑
- B: 所有帧走二进制 + 自定义帧头 → 放弃，前端解析复杂度大增

**理由**：保持二进制通道简单，JSON 通道天然可读可扩展，前后端改动最小。

### D6: finalizeTurn 流式化 — 后置 doFinally

**选择**：`streamHandle()` 返回的 Flux 在 `doFinally` 中收集完整回复文本，执行合规后检（兜底）、写短期记忆（ASSISTANT + TURN summary）、写会话状态。异常路径也需写一条 fallback 记忆。

**理由**：流式场景下，完整回复文本只能在流结束时获得。`doFinally` 覆盖正常完成和异常两种路径。如果流中途失败，写一条含 fallback 文本的 ASSISTANT 记忆，确保下一轮 IntentService 的 recent turns 不缺轮。

## Risks / Trade-offs

- **[Risk] 新 prompt 输出质量不稳定** → 缓解：emotion-merged.txt 的 prompt 强约束纯文本输出 + 禁止 JSON/Markdown；parseSpeech 增加 markdown 包裹兜底。灰度阶段可用 A/B 对比旧 prompt 质量。
- **[Risk] SentenceAggregator 50ms 阈值不适用所有语速** → 缓解：阈值做成配置项 `voice-shopping.streaming.sentence-aggregate-timeout-ms`，默认 50ms，可根据线上数据调优。
- **[Risk] 流式中途异常导致记忆缺轮** → 缓解：doFinally 中异常路径写 fallback 记忆；前端收到 `stream_done` 后若之前有 `error` 信号，展示降级提示。
- **[Risk] 合规逐句检查可能拦截部分内容，导致前后句不连贯** → 缓解：合规拦截时替换为占位文本而非丢弃整句，保持语音流畅度。当前合规为占位实现，此风险为远期风险。
- **[Trade-off] 产品卡片必须等推荐完成才能发出**：这是不可避免的，因为产品列表依赖推荐管道的完整输出。用户体感是：ASR 结束 → ~1s 等待 → 卡片弹出 → 字幕+语音同步流出。总延迟中推荐管道本身 ~600ms 不可压缩。

## Migration Plan

1. **阶段一**：后端改动（合并 LLM + 流式输出），WebSocket 暂时保持同步模式——`streamHandle` 内部用 `.blockLast()` 转同步，验证逻辑正确性
2. **阶段二**：WebSocket 切换为异步流式，前端 H5 改造
3. **回滚**：`EmotionAgentBuilder.PROMPT_FILE` 改回 `emotion.txt` + `ParallelRecommendService` 恢复 `reasonService` 注入 + `VoiceWebSocketHandler` 恢复同步调用
