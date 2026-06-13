## Why

语音购物链路 `ASR → Agent → TTS` 中，情感应答 Agent 是离用户最近的一环，它的产出直接进 TTS 变成声音。当前 `RecAgent` 已能产出 `RecommendResult`（含 reason），但包装层缺失：`EmotionAgentBuilder.build()` 仍是空壳返回 `null`、`emotion.txt` 是空 TODO。结果是推荐数据无法被转成自然口语，且 orchestrator 一旦调用 `getEmotionAgent(sessionId)` 会拿到被缓存进 `AgentSet` 的 null 直接 NPE。本次补齐这条链路的最后一环。

## What Changes

- 实现 `EmotionAgentBuilder.build()`：`mainChatModel`(qwen-max) + `emotion.txt` + `InMemoryMemory`，`name=emotion_agent`，与 `RecAgentBuilder` 范式一致。
- 填充 `emotion.txt`：口语包装 Prompt，统一为 **JSON 输出** `{ "speechText": "..." }`，含按会话情绪分风格的开场、每款一句主体、抛选择收尾，以及合规红线（禁绝对化用语、不承诺降价）与空结果温和引导分支。
- 新增 `SessionMoodDetector`：**纯规则**（无 LLM）的情绪检测，返回 `neutral / hesitant / impatient / positive / negative`。
- 新增 `EmotionService.wrap(sessionId, utterance, rec)`：检测情绪 → 构造精简 userMsg（products 只带 `name + reason`）→ 调 agent → JSON 解析 → try/catch fallback 兜底。
- 新增 `EmotionResult(speechText, displayBlocks)` Record：`speechText` 喂 TTS，`displayBlocks` 原样透传 `rec.items()` 供前端卡片，刻意分离语音版与视觉版。
- 新增 `EmotionDebugController` (`POST /api/v1/agent/emotion`) + `EmotionDebugReq(utterance, rec)` 调试接口。

## Capabilities

### New Capabilities

- `emotion-agent`: `EmotionAgentBuilder` 行为配置（qwen-max / emotion.txt / InMemoryMemory）与口语包装 Prompt 的结构契约。
- `emotion-service`: `EmotionService.wrap` 编排流程、`EmotionResult` DTO、fallback 兜底语义、`EmotionDebugController` 调试接口。
- `emotion-mood-detection`: `SessionMoodDetector` 纯规则情绪检测的优先级链与兜底。

### Modified Capabilities

- `agent-builder-skeleton`: `EmotionAgentBuilder` 由 `TODO — 暂返回 null` 升级为已实现；`emotion.txt` 由 `TODO` 升级为已填充。

## Impact

- **新增代码**：`voice-shopping-ai`（EmotionAgentBuilder 实现 + emotion.txt 填充）；`voice-shopping-business`（EmotionService、SessionMoodDetector，置于 `business/agent` 包）；`voice-shopping-common`（EmotionResult，置于 `common.dto.agent`）；`voice-shopping-web`（EmotionDebugController + EmotionDebugReq，DebugReq 置于 `web.dto`）。
- **复用既有件**：`AgentFactory.getEmotionAgent`（已接线）、`ShortTermMemory.recent`（detector 犹豫检测）、`RecommendResult/RecommendedItem`、`IntentService.extractJson` 的 JSON 提取范式。
- **不改 orchestrator 状态机**：本次仅暴露 `wrap()` 能力与调试接口，orchestrator 真正接入（`GENERATING_SPEECH` 状态调用）留待后续 change。
- **Scope 边界**：只覆盖"包装推荐结果"分支。`CHITTAT` / `OUT_OF_SCOPE` 的纯闲聊应答本次不做，`explanationTone="empty"` 分支能兜住"无商品"场景，纯对话入口后续再扩。
- **待查证项**：AgentScope 1.0.11 的 `InMemoryMemory` 是否内置历史裁剪；若无，EmotionService 需在调用前手动 trim，避免跨轮累积膨胀。
