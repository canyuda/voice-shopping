## ADDED Requirements

### Requirement: EmotionAgentBuilder 实现

`EmotionAgentBuilder.build()` SHALL 返回配置完整的 `ReActAgent`，包含：
- name: `emotion_agent`
- model: `mainChatModel`（qwen-max）
- sysPrompt: `prompts/emotion.txt`（经 `PromptLoader` 加载）
- memory: `InMemoryMemory`

#### Scenario: build 返回可用 Agent
- **WHEN** 调用 `build()`
- **THEN** 返回非 null 的 ReActAgent，可正常调用 `agent.call()`，不再返回 null

### Requirement: emotion.txt 口语包装 Prompt

`prompts/emotion.txt` SHALL 定义"最后一道包装者"Prompt，约束 LLM 把推荐结果包装成 80–150 字自然口语供 TTS 朗读。Prompt SHALL 包含：

- 角色定义：语音导购的最后一道包装者。
- 输入字段说明：`userUtterance` / `sessionMood`(neutral|hesitant|impatient|positive|negative) / `explanationTone`(professional|empty) / `products`(含 name 与 reason)。
- 输出格式：严格 JSON `{ "speechText": "..." }`，speechText 为纯中文口语文本。
- 开场规则：按 sessionMood 选风格（neutral 直入 / hesitant 带信心 / impatient 精简无寒暄 / negative 先承接情绪 / positive 轻量回应），并引用用户需求 1–2 个关键词。
- 主体规则：按"第一款/第二款"顺序，每款仅商品名 + 一条 reason，不展开参数，不编造输入外信息。
- 收尾规则：必须抛一个带选择的问题，禁止客套话。
- 合规红线：禁用绝对化用语（最好/最便宜/第一/保证/绝对/肯定）、不承诺价格下降、不贬低输入外商品、不命令式劝导。
- empty 分支：当 `explanationTone=="empty"` 时输出温和引导语（放宽预算/换品牌），不假装有推荐。

#### Scenario: 输出严格 JSON
- **WHEN** Agent 收到含 products 的 userMsg
- **THEN** 返回 `{ "speechText": "<80-150字口语>" }`，可被 JSON 解析提取出 speechText

#### Scenario: empty 分支不假装推荐
- **WHEN** Agent 收到 explanationTone="empty"
- **THEN** speechText 为引导语，不出现任何商品名

### Requirement: emotion agent 保留跨轮记忆

EmotionAgent SHALL 保留 InMemoryMemory 跨轮累积，不在每次 `wrap()` 调用前清空，使 Agent 能承接上一轮 speechText。

#### Scenario: 跨轮不清空
- **WHEN** 同一 session 连续两次调用 EmotionService.wrap
- **THEN** 第二次调用前不执行 `agent.getMemory().clear()`，memory 中保留上一轮对话内容
