# rec-reason-generation

## Purpose

Recommendation reason generation capability: LLM-based natural reason writing per product via RecAgentBuilder (mainChatModel/qwen-max) + RecommendReasonService, driven by prompts/rec.txt.

## Requirements

### Requirement: RecAgentBuilder 实现
系统 SHALL 实现 `RecAgentBuilder.build()` 方法，使用 `mainChatModel`（qwen-max），`InMemoryMemory`，加载 `prompts/rec.txt`，agent name 为 `recommend_agent`。

#### Scenario: RecAgentBuilder 构建非空 Agent
- **WHEN** 调用 `recAgentBuilder.build()`
- **THEN** 返回非 null 的 ReActAgent 实例，name 为 "recommend_agent"

### Requirement: 推荐理由 Prompt 文件
系统 SHALL 在 `voice-shopping-ai/src/main/resources/prompts/rec.txt` 维护推荐理由生成 Prompt，要求 LLM 对每款商品生成一句话（30 字以内）推荐理由，输出 JSON 数组格式。

#### Scenario: Prompt 文件存在且非空
- **WHEN** 通过 PromptLoader 加载 "rec.txt"
- **THEN** 返回非空字符串，包含角色定义、输入格式说明、输出格式说明

### Requirement: RecommendReasonService 理由生成
系统 SHALL 在 `com.voiceshopping.business.rec` 包下提供 `RecommendReasonService`，实现 `List<RecommendedItem> attachReasons(String sessionId, String userNeeds, List<RecommendedItem> products)` 方法。

实现步骤：
1. 通过 AgentFactory 获取 recAgent
2. 构造 JSON 格式 userMsg，含 userNeeds 和 products 列表
3. 调用 agent 获取 LLM 响应
4. 解析 JSON 响应，用 `withReason` 填充每款商品的推荐理由
5. try-catch 降级：LLM 调用失败时返回原 products（空理由）

#### Scenario: 正常生成推荐理由
- **WHEN** 调用 `attachReasons("sess1", "预算500跑鞋", 3个商品)`
- **THEN** 返回 3 个 RecommendedItem，每个 reason 为非空字符串

#### Scenario: LLM 调用失败降级
- **WHEN** LLM 调用抛出异常
- **THEN** 返回原始 products 列表，reason 为原始值（可能为空）

#### Scenario: LLM 输出格式不合规降级
- **WHEN** LLM 返回非 JSON 格式或 productId 不匹配
- **THEN** 返回原始 products 列表，reason 为原始值
