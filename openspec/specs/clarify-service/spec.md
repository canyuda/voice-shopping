## Purpose

规则+LLM 混合编排服务。规则层判断槽位是否充足，不足则截断后调 LLM 生成自然追问。

## Requirements

### Requirement: ClarifyService 规则+LLM 编排

系统 SHALL 提供 `ClarifyService.decide(sessionId, utterance, slots)` 方法，编排规则层和 LLM 层做出追问/就绪决策。

处理流程：
1. 从 slots 取 category
2. 调 `ClarifyRuleService.missingSlots()`
3. 缺失为空 → 返回 `ClarifyResult.ready()`
4. 缺失为 1 个字段时，从 `application.yml` 的 `voice-shopping.clarify.single-slot-templates` 表查模板问句：
   - 命中 → 直接返回 `ClarifyResult.ask(template, missingSlots)`，MUST NOT 调 LLM
   - 未命中（slot 名不在表内） → 走 LLM 路径
5. 缺失 > 2 个 → 截断到前 2 个
6. 获取 ClarifyAgent，清空记忆，构造 userMsg 调用 LLM
7. 返回 `ClarifyResult.ask(question, truncatedMissingSlots)`

`single-slot-templates` 配置在 `application.yml` 中，结构：
```yaml
voice-shopping:
  clarify:
    single-slot-templates:
      scenario: "你一般在什么场地用？"
      budget: "你预算大概多少？"
      gender: "是男款还是女款？"
      brand: "你有想要的品牌吗？"
```

#### Scenario: 单字段且模板命中跳过 LLM
- **WHEN** missingSlots = ["budget"]
- **THEN** 直接返回 `ClarifyResult { action: ASK, questionToAsk: "你预算大概多少？", missingSlots: ["budget"] }`
- **THEN** ClarifyAgent.call 不被调用一次

#### Scenario: 单字段但模板未命中走 LLM
- **WHEN** missingSlots = ["unknownSlot"]，配置表中无此 key
- **THEN** 走 LLM 路径生成问句

#### Scenario: 多字段走 LLM
- **WHEN** missingSlots = ["scenario", "budget"]
- **THEN** ClarifyAgent.call 被调用一次，返回的 questionToAsk 由 LLM 生成

#### Scenario: 槽位充足直接就绪
- **WHEN** slots 包含所有必填字段
- **THEN** 返回 `ClarifyResult.ready()`，ClarifyAgent.call 不被调用

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

### Requirement: ClarifyTemplateProperties 配置类

系统 SHALL 提供 `ClarifyTemplateProperties` `@ConfigurationProperties(prefix = "voice-shopping.clarify")` 类，将 YAML 中的 `single-slot-templates` 映射为 `Map<String, String>`，并通过依赖注入提供给 `ClarifyService`。

约束：
- key 为 slot 名（如 `scenario` / `budget` / `gender` / `brand`）
- value 为对应的模板问句（中文，≤ 20 字推荐）
- Map 默认空（如果配置未提供）；空 Map 时单字段也走 LLM（保底）

#### Scenario: 配置加载
- **WHEN** application.yml 配置 4 个模板，启动后注入 ClarifyTemplateProperties
- **THEN** `properties.singleSlotTemplates()` 返回 size=4 的 Map，按配置内容填充

#### Scenario: 配置缺失保底
- **WHEN** application.yml 中未配置 `voice-shopping.clarify.single-slot-templates`
- **THEN** 单字段 missing 一律走 LLM 路径

### Requirement: ClarifyService 单字段模板路径不打成本日志

`ClarifyService` 走单字段模板路径（未调 LLM）时 MUST NOT 调用 `CostMetricsLogger.logLlm`，因为没有发生 LLM 调用。

#### Scenario: 模板命中无 LLM 成本日志
- **WHEN** ClarifyService 走单字段模板路径
- **THEN** `cost-metrics.log` 中**不出现**该次调用对应的 `scene=LLM agent=clarify` 行
