## ADDED Requirements

### Requirement: EmotionService 包装编排

系统 SHALL 在 `com.voiceshopping.business.agent` 提供 `EmotionService.wrap(String sessionId, String userUtterance, RecommendResult rec)` 方法，编排情绪检测与口语包装：

1. 调 `SessionMoodDetector.detect(sessionId, userUtterance)` 取 mood。
2. 构造 userMsg，其中 products 仅包含每个 item 的 `name + reason`（剥离 price/matchScore/attributes）。
3. 经 `AgentFactory.getEmotionAgent(sessionId)` 获取 agent，发送 userMsg 调用。
4. 用花括号深度匹配提取 JSON 并解析 `speechText`。
5. 返回 `new EmotionResult(speechText, rec.items())`。

#### Scenario: 正常包装推荐
- **WHEN** wrap 收到含 3 个 item 的 rec，且 mood 检测为 neutral
- **THEN** 返回 EmotionResult，speechText 为口语化推荐语，displayBlocks 为完整 3 个 item

#### Scenario: products 输入精简
- **WHEN** 构造喂给 LLM 的 userMsg
- **THEN** products 数组每项仅含 name 与 reason，不含 price/matchScore/attributes

#### Scenario: displayBlocks 透传完整数据
- **WHEN** rec.items() 含 price 与 attributes
- **THEN** EmotionResult.displayBlocks 保留这些字段供前端展示

### Requirement: EmotionResult DTO

系统 SHALL 在 `com.voiceshopping.common.dto.agent` 提供 `EmotionResult(String speechText, List<RecommendedItem> displayBlocks)` Record。

#### Scenario: 字段分离
- **WHEN** 构造 EmotionResult
- **THEN** speechText 为喂 TTS 的口语文本，displayBlocks 为前端商品卡片数据源

### Requirement: wrap 的 fallback 兜底

`wrap()` SHALL 以 try/catch 包裹 agent 调用与 JSON 解析；任意异常时返回兜底 EmotionResult，不向上抛出：

- `rec.items()` 为空 → speechText = "这个条件下合适的不多，要不要放宽点预算再看看？"
- `rec.items()` 非空 → speechText = "好，给你挑了几款。" + 遍历每个 item 的 name + reason + "你看看选哪个？"

兜底时 displayBlocks SHALL 仍为 `rec.items()`。

#### Scenario: items 非空时 LLM 失败
- **WHEN** agent 调用抛异常或返回非 JSON，且 rec.items() 非空
- **THEN** 返回兜底 speechText，含每个 item 的 name 与 reason，displayBlocks 透传

#### Scenario: items 为空时失败
- **WHEN** agent 调用失败，且 rec.items() 为空
- **THEN** 返回 speechText = "这个条件下合适的不多，要不要放宽点预算再看看？"

### Requirement: EmotionDebugController 调试接口

系统 SHALL 提供 `POST /api/v1/agent/emotion` 调试接口，`sessionId` 经 `@RequestParam` 传入，请求体为 `EmotionDebugReq(String utterance, RecommendResult rec)`（置于 `com.voiceshopping.web.dto`），返回 `ApiResult<EmotionResult>`。

#### Scenario: 调试调用
- **WHEN** POST 请求带 sessionId、utterance="我想买双缓震好的跑鞋"、rec 含 2 个 item
- **THEN** 返回 ApiResult.ok(EmotionResult)，含 speechText 与 displayBlocks
