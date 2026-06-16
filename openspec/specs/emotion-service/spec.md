## Purpose

EmotionService 编排情绪检测与口语包装，将推荐结果转换为 TTS 口语文本，并透传完整商品卡片数据给前端。

## Requirements

### Requirement: EmotionService 包装编排

系统 SHALL 在 `com.voiceshopping.business.agent` 提供 `EmotionService.wrap(String sessionId, String userUtterance, String userNeeds, RecommendResult rec)` 方法，编排情绪检测与口语包装：

1. 调 `SessionMoodDetector.detect(sessionId, userUtterance)` 取 mood。
2. 构造 userMsg，其中 products 包含每个 item 的 `name + price + attributes`（剥离 matchScore/reason），新增 `userNeeds` 字段，去掉 `explanationTone` 字段。
3. 经 `AgentFactory.getEmotionAgent(sessionId)` 获取 agent，发送 userMsg 调用。
4. 解析输出：直接取 rawText，strip 掉 ```json ... ``` 包裹后作为 speechText；若结果为空或异常则走 fallback。
5. 返回 `new EmotionResult(speechText, rec.items())`。

userNeeds 由 slots 转换而来，格式为 `slot1Key=slot1Value,slot2Key=slot2Value,...`。

#### Scenario: 正常包装推荐
- **WHEN** wrap 收到含 3 个 item 的 rec，userNeeds="category=跑鞋,budget=500"，且 mood 检测为 neutral
- **THEN** 返回 EmotionResult，speechText 为纯口语文本（无 JSON 包裹、无 Markdown），displayBlocks 为完整 3 个 item

#### Scenario: products 输入含属性
- **WHEN** 构造喂给 LLM 的 userMsg
- **THEN** products 数组每项含 name、price、attributes，不含 matchScore/reason

#### Scenario: userNeeds 字段正确填充
- **WHEN** slots = {category: "跑鞋", budget: 500}
- **THEN** userNeeds = "category=跑鞋,budget=500"

#### Scenario: displayBlocks 透传完整数据
- **WHEN** rec.items() 含 price 与 attributes
- **THEN** EmotionResult.displayBlocks 保留这些字段供前端展示

#### Scenario: 模型偶发 markdown 包裹输出
- **WHEN** Agent 返回 "```json\n别纠结，鸡哥给你挑了三款。```"
- **THEN** parseSpeech 正确提取 "别纠结，鸡哥给你挑了三款。"

### Requirement: EmotionResult DTO

系统 SHALL 在 `com.voiceshopping.common.dto.agent` 提供 `EmotionResult(String speechText, List<RecommendedItem> displayBlocks)` Record。

#### Scenario: 字段分离
- **WHEN** 构造 EmotionResult
- **THEN** speechText 为喂 TTS 的口语文本，displayBlocks 为前端商品卡片数据源

### Requirement: wrap 的 fallback 兜底

`wrap()` SHALL 以 try/catch 包裹 agent 调用与输出解析；任意异常时返回兜底 EmotionResult，不向上抛出：

- `rec.items()` 为空 → speechText = "这个条件下合适的不多，要不要放宽点预算再看看？"
- `rec.items()` 非空 → speechText = "好，给你挑了几款。" + 遍历每个 item 的 name + reason（reason 为空时仅使用 name） + "你看看选哪个？"

兜底时 displayBlocks SHALL 仍为 `rec.items()`。

#### Scenario: items 非空时 LLM 失败
- **WHEN** agent 调用抛异常或返回空文本，且 rec.items() 非空
- **THEN** 返回兜底 speechText，含每个 item 的 name，displayBlocks 透传

#### Scenario: items 为空时失败
- **WHEN** agent 调用失败，且 rec.items() 为空
- **THEN** 返回 speechText = "这个条件下合适的不多，要不要放宽点预算再看看？"

### Requirement: EmotionDebugController 调试接口

系统 SHALL 提供 `POST /api/v1/agent/emotion` 调试接口，`sessionId` 经 `@RequestParam` 传入，请求体为 `EmotionDebugReq(String utterance, RecommendResult rec, String userNeeds)`（置于 `com.voiceshopping.web.dto`），返回 `ApiResult<EmotionResult>`。`userNeeds` 可选，默认为空字符串。

#### Scenario: 带 userNeeds 调试
- **WHEN** POST `/api/v1/agent/emotion` 带 userNeeds="category=跑鞋,budget=500"
- **THEN** EmotionService.wrap 接收该 userNeeds 参数

#### Scenario: 不带 userNeeds 调试
- **WHEN** POST `/api/v1/agent/emotion` 不传 userNeeds
- **THEN** EmotionService.wrap 接收空字符串 userNeeds
