## 1. DTO 与 Prompt 基础

- [x] 1.1 在 `com.voiceshopping.common.dto.agent` 新建 `EmotionResult(String speechText, List<RecommendedItem> displayBlocks)` Record
- [x] 1.2 填充 `voice-shopping-ai/src/main/resources/prompts/emotion.txt`：最后一道包装者角色，严格 JSON `{ "speechText": "..." }` 输出，按 sessionMood 分风格开场、每款一句主体、抛选择收尾、合规红线、`explanationTone=="empty"` 引导分支

## 2. EmotionAgentBuilder 实现

- [x] 2.1 在 `EmotionAgentBuilder.build()` 注入 `mainChatModel` + `PromptLoader`，返回 `ReActAgent`（name=`emotion_agent`、sysPrompt=`emotion.txt`、`InMemoryMemory`），不再返回 null
- [x] 2.2 验证 `AgentFactory.getEmotionAgent(sessionId)` 返回非 null 实例（消除缓存 null 导致的 NPE）

## 3. SessionMoodDetector

- [x] 3.1 在 `com.voiceshopping.business.agent` 新建 `SessionMoodDetector`，实现 `byRule`：`IMPATIENT > NEGATIVE > POSITIVE` 正则优先级链（IMPATIENT=`算了|不要|别说了|快点|到底|跳过`，NEGATIVE=`贵|太贵|不喜欢|丑|不行|不对`，POSITIVE=`好的|可以|不错|挺好|行|ok`），null 输入返回 null
- [x] 3.2 实现犹豫检测：`ShortTermMemory.recent(sessionId, 3)` 过滤 `role=ASSISTANT` 的 `content`，问号结尾 ≥2 返回 `hesitant`
- [x] 3.3 兜底返回 `neutral`；确认 `detect` 全程不调用 `lightChatModel`

## 4. EmotionService

- [x] 4.1 在 `com.voiceshopping.business.agent` 新建 `EmotionService.wrap(sessionId, userUtterance, rec)`：detect mood → 构造 userMsg（products 仅 `name+reason`）→ `getEmotionAgent` 调用 → 花括号深度匹配提取 JSON 解析 `speechText`
- [x] 4.2 实现 try/catch fallback 兜底（items 空 → "这个条件下合适的不多，要不要放宽点预算再看看？"；非空 → "好，给你挑了几款。"+name+reason+"你看看选哪个？"），displayBlocks 透传 `rec.items()`
- [x] 4.3 查证 AgentScope 1.0.11 `InMemoryMemory` 是否内置历史裁剪；若无，在 `wrap` 调用前加 trim/定期 clear 策略（保留跨轮但限膨胀）

## 5. 调试接口

- [x] 5.1 在 `com.voiceshopping.web.dto` 新建 `EmotionDebugReq(String utterance, RecommendResult rec)` Record
- [x] 5.2 在 `com.voiceshopping.web.controller` 新建 `EmotionDebugController`，`POST /api/v1/agent/emotion`（`@RequestParam sessionId` + `@RequestBody EmotionDebugReq`），返回 `ApiResult<EmotionResult>`

## 6. 验证

- [x] 6.1 `mvn -q compile` 全模块编译通过
- [x] 6.2 调试接口端到端：有 rec（normal）/ explanationTone=empty（引导）/ 构造 LLM 失败（fallback）三种场景验证
