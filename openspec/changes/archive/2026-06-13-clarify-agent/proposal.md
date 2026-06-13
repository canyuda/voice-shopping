## Why

IntentAgent 完成意图识别和槽位抽取后，如果槽位不足以支撑商品检索，需要追问用户补充信息。当前的 ClarifyAgentBuilder 仅返回 null，缺少规则判断、LLM 话术包装和 Service 层编排。实现需求澄清链路是连接意图识别和商品推荐的关键环节。

## What Changes

- 新增 `required-slots.yml` — 按品类维护的必填槽位配置表（放在 voice-shopping-ai 的 resources/clarify/ 下）
- 新增 `ClarifyRuleService` — 解析 YAML 配置，提供 `missingSlots(category, slots)` 方法
- 充实 `prompts/clarify.txt` — 自然追问生成 Prompt
- 实现 `ClarifyAgentBuilder.build()` — lightChatModel + InMemoryMemory
- 新增 `ClarifyResult` — 澄清决策 DTO（ASK/READY + 追问文本 + 缺失字段）
- 新增 `ClarifyService.decide()` — 规则优先、LLM 包装话术的编排服务
- 新增 `ClarifyDebugController` — POST /api/v1/agent/clarify 调试接口

## Capabilities

### New Capabilities

- `clarify-rule`: 品类槽位规则引擎，从 YAML 配置加载 required/nice_to_have/scenario_options，计算缺失槽位
- `clarify-agent`: ClarifyAgentBuilder + clarify.txt prompt，LLM 将缺失字段包装为自然追问
- `clarify-service`: 编排规则层和 LLM 层，规则层为空直接 READY，否则截断 ≤2 个缺失字段后调 LLM

### Modified Capabilities

<!-- No existing capabilities have spec-level requirement changes -->

## Impact

- 影响模块：`voice-shopping-ai`（prompt、Builder、YAML 配置）、`voice-shopping-business`（RuleService、Service）、`voice-shopping-common`（ClarifyResult DTO）、`voice-shopping-web`（DebugController）
- 无破坏性变更
