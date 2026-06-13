## Context

语音购物主链路 `ASR → Agent → TTS` 已实现到 `RecommendOrchestrator`：能产出含 `reason` 的 `RecommendResult`。但"最后一公里"——把推荐数据包装成供 TTS 朗读的自然口语——尚未落地：

- `EmotionAgentBuilder.build()` 是空壳，返回 `null` 并带 TODO。
- `emotion.txt` 仅一行 `# TODO: fill prompt content`。
- `AgentFactory` 已把 emotion 接线完毕（注入 builder、`AgentSet.emotionAgent`、`getEmotionAgent(sessionId)`），但 `get()` 会把这个 null 缓存进 session 级 `AgentSet`——orchestrator 一旦调用即 NPE。

复用件已就绪：`mainChatModel`(qwen-max) / `lightChatModel`(qwen-turbo) Bean、`PromptLoader`、`AgentFactory.getEmotionAgent`、`ShortTermMemory.recent(sessionId, n)`（注意 `Turn` 字段是 `content` 非 `text`）、`RecommendResult(items, explanationTone)` 与 `RecommendResult.EMPTY`(tone="empty")、`IntentService.extractJson` 的 JSON 提取范式、`RecommendDebugController` 的调试接口范式。

## Goals / Non-Goals

**Goals:**

- 将 `RecommendResult` 包装为 80–150 字自然口语 `speechText`，供 TTS 合成。
- 提供零 LLM 延迟、纯规则的情绪检测（`SessionMoodDetector`）。
- `speechText`（语音版，精简）与 `displayBlocks`（视觉版，完整商品卡片）刻意分离。
- LLM 调用或解析失败时有可用的兜底口语（fail-soft，因 emotion 是链路终点不可向上抛）。
- 提供 `/api/v1/agent/emotion` 调试接口，可独立验证 emotion 环节。

**Non-Goals:**

- 不接入 orchestrator 状态机（`GENERATING_SPEECH` 状态调用留待后续 change）。
- 不处理 `CHITCHAT` / `OUT_OF_SCOPE` 纯闲聊应答（`explanationTone="empty"` 分支部分覆盖"无商品"场景）。
- 不做 service 层合规词正则后处理（先靠 prompt 约束，后续加固）。
- 不重构既有 agent / memory 机制。

## Decisions

### D1: SessionMoodDetector 纯规则，不接 LLM

**选择**：`detect(sessionId, utterance)` 走 `Pattern` 正则 → 犹豫检测 → `neutral` 兜底，全程不调用 `lightChatModel`。

**优先级链**：`byRule` 内 `IMPATIENT > NEGATIVE > POSITIVE`（IMPATIENT 先判，避免"不要"同时含否定语义时被误判为 negative）；`byRule` 返回 null 后查 `ShortTermMemory.recent(3)`，过滤 `role=ASSISTANT` 的 `content`，问号结尾 ≥2 → `hesitant`；兜底 `neutral`。

**理由**：情绪检测处于每轮语音对话的热路径，规则覆盖 80% 高频场景（催促/肯定/否定/犹豫），零额外 LLM 延迟与成本；与 `ClarifyRuleService`"规则先行"哲学一致。

**备选**：规则未命中时调 qwen-turbo 兜底。**否决**：emotion 本就在 TTS 前的最后一跳，延迟敏感；规则未命中落 neutral 已满足"不破坏体验"的下限。

### D2: emotion agent 输出严格 JSON `{ "speechText": "..." }`

**选择**：统一为 JSON 输出，复用 `IntentService.extractJson` 的花括号深度匹配 + Jackson 解析范式。

**理由**：与 IntentAgent 解析路径统一；结构化输出便于判定 fallback（解析失败即兜底）；未来可平滑扩展字段（语气标记、置信度等）。

**备选**：纯文本直接取 `rawText`。**否决**：与既有 agent 解析范式不一致，且失去结构化扩展性。

**修正**：原设计中 prompt 的"只输出 speechText（纯文本）"与"严格 JSON"自相矛盾。统一为 JSON，把"纯文本"表述改为"speechText 字段为纯中文口语文本"。

### D3: emotion agent 保留跨轮 memory（不 clear）

**选择**：`EmotionService` 不调用 `agent.getMemory().clear()`，依赖 `AgentFactory` 的 session 级缓存让 `InMemoryMemory` 跨轮持久。

**理由**：`AgentFactory` 注释明确"cached per session so InMemoryMemory persists across turns"；保留 memory 使 emotion 能承接上一轮（如用户追问"第二款再细说"，agent 可参考上轮 speechText）。`IntentService` 的 clear 是"意图识别必须无状态"的特例，不适用于会话感知的 emotion。

**备选**：每轮 clear（与 IntentService 一致）。**否决**：emotion 输入虽自包含，但跨轮连续性对追问/细化场景有实际价值，且更贴合工厂设计意图。

### D4: 喂给 emotion 的 products 只含 `name + reason`

**选择**：构造 userMsg 时将 `rec.items()` 映射为 `[{ "name": ..., "reason": ... }]`，剥离 `price / matchScore / attributes`。`displayBlocks` 仍原样透传完整 `rec.items()`。

**理由**：从输入侧工程保证 LLM 不可能念出价格（守住"不承诺价格"合规红线），呼应 speechText 精简 / displayBlocks 信息量大的分离设计；同时减少 token。

**备选**：传完整 item。**否决**：增加 prompt 跑偏风险（LLM 可能念价格/参数），违背精简口语目标。

### D5: fallback 兜底语义

**选择**：agent 调用或 JSON 解析异常时，按 `rec.items()` 是否为空兜底——空 → "这个条件下合适的不多，要不要放宽点预算再看看？"；非空 → "好，给你挑了几款。" + 遍历 `name + reason` + "你看看选哪个？"。

**理由**：fail-fast 原则下，emotion 作为链路终点不可向上抛（TTS 必须拿到文本），故用 try/catch 降级为可用的兜底口语，与空结果引导语语义一致。

### D6: 包结构遵循既有约定（修正设计中的两处错误）

**选择**：
- `EmotionResult` → `com.voiceshopping.common.dto.agent`（非原设计的 `com.jichi.voiceshopping.dto`）。
- `EmotionService` / `SessionMoodDetector` → `com.voiceshopping.business.agent`（与 `IntentService` / `ClarifyService` 并列）。
- `EmotionDebugController` → `com.voiceshopping.web.controller`；`EmotionDebugReq` → `com.voiceshopping.web.dto`（与 `RecommendDebugReq` 一致）。
- 犹豫检测读 `Turn.content()`（非 `text`）。

**理由**：遵循 CLAUDE.md 的 DTO 归属规定与现有包约定，保持模块内聚。

### D7: scope 仅交付 wrap 能力 + 调试接口

**选择**：本次不改 orchestrator 状态机。

**理由**：聚焦、可独立验证；orchestrator 接入涉及 `GENERATING_SPEECH` 状态与多分支（RECOMMEND/COMPARE/CHITCHAT）汇聚，独立 change 更安全。

## Risks / Trade-offs

- **[InMemoryMemory 跨轮膨胀]** → 待查 AgentScope 1.0.11 的 `InMemoryMemory` 是否内置历史裁剪；若无，`EmotionService` 调用前手动 trim 或定期 clear（保守方案，见 Open Questions）。
- **[纯规则情绪漏判长尾]** → 规则未命中统一落 `neutral`，体验降级但不破坏；后续可加 qwen-turbo 兜底（非本次 scope）。
- **[合规红线纯靠 prompt 不可靠]** → D4 已从输入侧剥离价格作工程兜底；service 层正则后处理（禁用词替换）作为后续加固项。
- **[JSON 解析失败]** → D5 fallback 兜底，保证 TTS 永远拿到文本。
- **[AgentFactory 已缓存 null emotionAgent]** → 实现 `build()` 是前置硬条件，NPE 风险随本 change 实现而消除。

## Open Questions

- AgentScope 1.0.11 的 `InMemoryMemory` 是否内置历史裁剪机制？若否，需在 `EmotionService` 决定 trim 策略（按轮数上限裁剪 vs 定期 clear）。实现阶段查证。
