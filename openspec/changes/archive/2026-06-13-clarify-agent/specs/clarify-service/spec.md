## ADDED Requirements

### Requirement: ClarifyService 规则+LLM 编排

系统 SHALL 提供 `ClarifyService.decide(sessionId, utterance, slots)` 方法，编排规则层和 LLM 层做出追问/就绪决策。

处理流程：
1. 从 slots 取 category
2. 调 `ClarifyRuleService.missingSlots()`
3. 缺失为空 → 返回 `ClarifyResult.ready()`
4. 缺失 > 2 个 → 截断到前 2 个
5. 获取 ClarifyAgent，清空记忆，构造 userMsg 调用 LLM
6. 返回 `ClarifyResult.ask(question, truncatedMissingSlots)`

#### Scenario: 槽位不足需追问
- **WHEN** slots 仅有 `{category: "跑鞋", budget: 500}`，缺失 scenario
- **THEN** 返回 `ClarifyResult { action: ASK, questionToAsk: "平时在哪里跑...", missingSlots: ["scenario"] }`

#### Scenario: 槽位充足直接就绪
- **WHEN** slots 包含 category、scenario、budget
- **THEN** 返回 `ClarifyResult { action: READY, questionToAsk: null, missingSlots: [] }`

#### Scenario: 缺失超过 2 个时截断
- **WHEN** missingSlots 返回 `["scenario", "budget", "brand", "gender"]`
- **THEN** 仅将 `["scenario", "budget"]` 传入 LLM，ClarifyResult.missingSlots 也为 `["scenario", "budget"]`

#### Scenario: userMsg 格式化
- **WHEN** 构造 LLM 的 userMsg
- **THEN** 格式为：
  ```
  用户原话：{utterance}
  已知信息：
  - category: 跑鞋
  - budget: 500元
  缺失字段：[scenario]
  ```

### Requirement: ClarifyResult DTO

系统 SHALL 提供 `ClarifyResult` Record，包含 action、questionToAsk、missingSlots 三个字段。

#### Scenario: 静态工厂方法
- **WHEN** 调用 `ClarifyResult.ready()`
- **THEN** 返回 action=READY, questionToAsk=null, missingSlots=空列表
- **WHEN** 调用 `ClarifyResult.ask("追问", ["slot1"])`
- **THEN** 返回 action=ASK, questionToAsk="追问", missingSlots=["slot1"]

### Requirement: ClarifyDebugController 调试接口

系统 SHALL 提供 `POST /api/v1/agent/clarify` 调试接口，请求体为 `ClarifyDebugReq { sessionId, utterance, slots }`，返回 `ApiResult<ClarifyResult>`。

#### Scenario: 调试调用
- **WHEN** POST 请求包含 sessionId、"我想买双跑鞋"、slots `{category: "跑鞋"}`
- **THEN** 返回 ASK 状态及追问文本和缺失字段
