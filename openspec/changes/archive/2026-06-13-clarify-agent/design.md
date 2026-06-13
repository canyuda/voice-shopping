## Context

IntentAgent 输出 `IntentResult { intent, slots, confidence }` 后，如果 intent 为 PRODUCT_RECOMMENDATION，slots 可能不完整（缺少 scenario、brand 等），需要 ClarifyAgent 决定是否追问及追问什么。当前 ClarifyAgentBuilder 的 `build()` 返回 null，需要完整实现规则 + LLM 混合链路。

## Goals / Non-Goals

**Goals:**
- 实现 `ClarifyRuleService`：按品类 YAML 配置计算缺失槽位
- 实现 `ClarifyAgentBuilder`：lightChatModel + classify prompt + InMemoryMemory
- 实现 `ClarifyService.decide()`：规则优先 → 截断兜底 → LLM 包装话术
- 调试接口 POST /api/v1/agent/clarify

**Non-Goals:**
- 不实现槽位合并逻辑（本轮仅追问，下轮槽位更新由 Orchestrator 负责）
- 不实现 LLM 失败降级（默认 LLM 一定返回有效文本）
- 不做 category 模糊匹配（用 `getOrDefault` 处理未知品类）

## Decisions

### 1. 规则 + LLM 混合架构

```
ClarifyService.decide(sessionId, utterance, slots)
│
├─ 规则层 ClarifyRuleService.missingSlots(category, slots)
│   → 为空 → ClarifyResult.ready()  （不走 LLM，零成本）
│   → 非空 → ↓
│
├─ 截断层 超过 2 个 → 只取前 2 个  （required 在前，niceToHave 在后）
│
├─ LLM 层 ClarifyAgent.包装话术(用户原话，已知信息，缺失字段)
│   → 返回自然追问文本
│
└─ ClarifyResult.ask(question, truncatedMissingSlots)
```

**为什么规则优先，而非纯 LLM：**
- 规则层零延迟、零成本，槽位够直接 READY 跳过 LLM
- LLM 职责单一（话术包装），不需要理解"哪些品类需要哪些字段"的领域知识
- 品类配置由产品/运营通过 YAML 维护，不依赖工程师改 prompt

### 2. YAML 配置放在 voice-shopping-ai 模块

虽然 `ClarifyRuleService` 在 business 层，但 `required-slots.yml` 和 prompt 同属 AI 能力的配置，放在 `voice-shopping-ai/src/main/resources/clarify/` 下集中管理。RuleService 通过 `ClassPathResource` 跨模块加载。

### 3. 截断在 Service 层而非 Prompt 层

Prompt 已强调"一次最多问 1-2 个字段"，但这是 LLM 的软约束，可能被忽略。Service 层硬截断从源头避免长追问：缺失字段在传入 LLM 之前就被裁剪到 ≤2 个，LLM 看不到多余的字段。同时返回给 Orchestrator 的 missingSlots 也是裁剪后的，SessionState 记录不会被污染。

### 4. knownSlots 格式化

`Map.toString()` 输出 `{category=跑鞋, budget=500}` 不自然。拼接 userMsg 时格式化为：

```
- category: 跑鞋
- budget: 500元
```

NULL 值不显示。

### 5. category 未知时的处理

`rules.getOrDefault(category, rules.get("default"))` — 用 default 规则兜底，不抛异常。default 规则是 `required: [category, budget]`，意味着至少需要品类和预算才 READY。

### 6. ClarifyAgent 无状态单轮

每次调用 `decide()` 前 `agent.getMemory().clear()`，和 IntentAgent 一致——追问是独立的单轮任务，不需要历史上下文。

## Risks / Trade-offs

- **LLM 返回空或异常** → 本版本不做降级处理，默认定成功。后续遇到线上问题后补充 READY 降级。
- **品类 YAML 膨胀** → 品类数增长后考虑加缓存（Spring `@Cacheable`），当前量级直接读取即可。
- **scenario_options 未传入 prompt** → CategoryRule 已包含此字段，prompt 中不主动注入，LLM 靠自身知识生成选项。后续可优化。
