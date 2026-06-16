## MODIFIED Requirements

### Requirement: EmotionAgentBuilder 实现

`EmotionAgentBuilder.build()` SHALL 返回配置完整的 `ReActAgent`，包含：
- name: `emotion_agent`
- model: `mainChatModel`（qwen-max）
- sysPrompt: **[CHANGED]** `prompts/emotion-merged.txt`（经 `PromptLoader` 加载，原 `emotion.txt` 保留但不再使用）
- memory: `InMemoryMemory`

`PROMPT_FILE` 常量 SHALL 从 `"emotion.txt"` 改为 `"emotion-merged.txt"`。

#### Scenario: build 返回使用新 prompt 的 Agent
- **WHEN** 调用 `build()`
- **THEN** 返回的 ReActAgent 的 sysPrompt 内容来自 `emotion-merged.txt`

### Requirement: emotion-merged.txt 口语包装 Prompt

`prompts/emotion-merged.txt` SHALL 定义合并推荐理由与情感包装的 Prompt，约束 LLM 一步生成口语文本。Prompt SHALL 包含：

- 角色定义：语音导购的最后一道输出，同时完成情绪风格选择与推荐理由生成。
- 输入字段说明：`userUtterance` / `sessionMood`(neutral|hesitant|impatient|positive|negative) / **`userNeeds`**(槽位转换的需求摘要) / `products`(含 productId/name/price/attributes)。
- **去掉** `explanationTone` 字段。
- 输出格式：**纯文本**，禁止 JSON / Markdown / 代码块。结构为"开场 → 第一款一句话 → 第二款一句话 → 第三款一句话 → 收尾问句"，80–150 字。
- 开场规则：按 sessionMood 选风格，引用 userNeeds 中的关键词。
- 主体规则：按"第一款/第二款"顺序，每款结合 attributes 中 1–2 个核心属性生成推荐理由。
- 收尾规则：必须抛一个带选择的问题，禁止客套话。
- 合规红线：禁用绝对化用语、不承诺价格下降、不贬低输入外商品、不命令式劝导。
- 硬约束：只输出口语文本，不要任何 JSON 包装、不要 Markdown、不要前后缀解释、不包含换行/列表符号/星号/井号。

#### Scenario: 输出纯文本
- **WHEN** Agent 收到含 products 的 userMsg
- **THEN** 返回纯口语文本（80–150 字），无 JSON 包裹、无 Markdown 格式

#### Scenario: 结合 attributes 生成理由
- **WHEN** products 含 [{name:"Asics GEL-Contend 9", price:479, attributes:{cushion:"high"}}]
- **THEN** 输出文本中提及该商品的缓震特性

#### Scenario: userNeeds 关键词被引用
- **WHEN** userNeeds="category=跑鞋,budget=500"
- **THEN** 输出文本中引用"跑鞋"或"500"关键词

### Requirement: emotion agent 保留跨轮记忆

EmotionAgent SHALL 保留 InMemoryMemory 跨轮累积，不在每次 `wrap()` 或 `streamWrap()` 调用前清空，使 Agent 能承接上一轮 speechText。

#### Scenario: 跨轮不清空
- **WHEN** 同一 session 连续两次调用 EmotionService.wrap 或 EmotionStreamingService.streamWrap
- **THEN** 第二次调用前不执行 `agent.getMemory().clear()`，memory 中保留上一轮对话内容
