## ADDED Requirements

### Requirement: ClarifyAgentBuilder 实现

`ClarifyAgentBuilder.build()` SHALL 返回一个配置完整的 `ReActAgent`，包含：
- name: `clarify_agent`
- model: `lightChatModel`（qwen-turbo）
- sysPrompt: `prompts/clarify.txt`
- memory: `InMemoryMemory`

#### Scenario: build 返回可用 Agent
- **WHEN** 调用 `build()`
- **THEN** 返回非 null 的 ReActAgent 实例，可正常调用 `agent.call()`

### Requirement: clarify.txt Prompt

`prompts/clarify.txt` SHALL 定义自然追问生成 Prompt，包含：
- 角色定义：专业导购
- 约束：一次最多问 1-2 个字段、口吻自然、不重复用户已说内容
- 输入格式：用户原话 / 已知信息 / 缺失字段
- 输出格式：纯文本追问

#### Scenario: LLM 输出纯文本
- **WHEN** Agent 收到包含缺失字段的 userMsg
- **THEN** 返回自然追问文本（非 JSON），可直接作为语音回复

### Requirement: 每次调用前清空记忆

ClarifyAgent SHALL 在每次 `decide()` 调用前清空 InMemoryMemory，保持无状态单轮行为。

#### Scenario: 清空记忆
- **WHEN** `ClarifyService.decide()` 调用 agent
- **THEN** 先执行 `agent.getMemory().clear()`，再发送 userMsg
