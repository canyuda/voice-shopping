## MODIFIED Requirements

### Requirement: EmotionService 包装编排

系统 SHALL 在 `com.voiceshopping.business.agent` 提供 `EmotionService.wrap(String sessionId, String userUtterance, String userNeeds, RecommendResult rec)` 方法，编排情绪检测与口语包装：

1. 调 `SessionMoodDetector.detect(sessionId, userUtterance)` 取 mood。
2. 构造 userMsg，其中 products 包含每个 item 的 `name + price + attributes`（剥离 matchScore/reason），新增 `userNeeds` 字段，**去掉** `explanationTone` 字段。
3. 经 `AgentFactory.getEmotionAgent(sessionId)` 获取 agent，发送 userMsg 调用。
4. 解析输出：直接取 rawText，strip 掉 `\`\`\`json ... \`\`\`` 包裹后作为 speechText；若结果为空或异常则走 fallback。
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
- **WHEN** Agent 返回 "\`\`\`json\n别纠结，鸡哥给你挑了三款。\`\`\`"
- **THEN** parseSpeech 正确提取 "别纠结，鸡哥给你挑了三款。"

## ADDED Requirements

### Requirement: EmotionDebugReq 新增 userNeeds

`EmotionDebugReq` Record SHALL 扩展为 `EmotionDebugReq(String utterance, RecommendResult rec, String userNeeds)`。`userNeeds` 可选，默认为空字符串。

#### Scenario: 带 userNeeds 调试
- **WHEN** POST `/api/v1/agent/emotion` 带 userNeeds="category=跑鞋,budget=500"
- **THEN** EmotionService.wrap 接收该 userNeeds 参数

#### Scenario: 不带 userNeeds 调试
- **WHEN** POST `/api/v1/agent/emotion` 不传 userNeeds
- **THEN** EmotionService.wrap 接收空字符串 userNeeds
