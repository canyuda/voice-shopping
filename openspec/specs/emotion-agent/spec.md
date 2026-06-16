## Purpose

EmotionAgent 的 Builder 和口语包装 Prompt。LLM 合并推荐理由生成与情感包装，把推荐结果包装成自然口语供 TTS 朗读，并按会话情绪与用户需求调整话术风格。

## Requirements

### Requirement: EmotionAgentBuilder 实现

`EmotionAgentBuilder.build()` SHALL 返回配置完整的 `ReActAgent`，包含：
- name: `emotion_agent`
- model: `mainChatModel`（qwen-max）
- sysPrompt: `prompts/emotion-merged.txt`（经 `PromptLoader` 加载，原 `emotion.txt` 保留但不再使用）
- memory: `InMemoryMemory`

`PROMPT_FILE` 常量 SHALL 从 `"emotion.txt"` 改为 `"emotion-merged.txt"`。

#### Scenario: build 返回使用新 prompt 的 Agent
- **WHEN** 调用 `build()`
- **THEN** 返回的 ReActAgent 的 sysPrompt 内容来自 `emotion-merged.txt`

### Requirement: emotion-merged.txt 口语包装 Prompt

`prompts/emotion-merged.txt` SHALL 定义合并推荐理由与情感包装的 Prompt，约束 LLM 一步生成口语文本。Prompt SHALL 包含：

- 角色定义：语音导购的最后一道输出，同时完成情绪风格选择与推荐理由生成。
- 输入字段说明：`userUtterance` / `sessionMood`(neutral|hesitant|impatient|positive|negative) / `userNeeds`(槽位转换的需求摘要) / `products`(含 productId/name/price/attributes)。
- 去掉 `explanationTone` 字段。
- 输出格式：纯文本，禁止 JSON / Markdown / 代码块。结构为"开场 → 第一款一句话 → 第二款一句话 → 第三款一句话 → 收尾问句"，50–100 字。
- 开场规则：按 sessionMood 选风格，引用 userNeeds 中的关键词。
- 主体规则：按"第一款/第二款"顺序，每款仅保留商品名 + 一句不超过 10 字的核心卖点，不展开属性（不写 `drop:5mm` 这类参数），例如将"中底厚实缓震好"压缩为"缓震最好"。
- 收尾规则：必须抛一个带选择的问题，禁止客套话。
- 合规红线：禁用绝对化用语、不承诺价格下降、不贬低输入外商品、不命令式劝导。
- 硬约束：只输出口语文本，不要任何 JSON 包装、不要 Markdown、不要前后缀解释、不包含换行/列表符号/星号/井号。

#### Scenario: 输出长度合规
- **WHEN** Agent 收到含 3 个 products 的 userMsg
- **THEN** 返回纯口语文本，总长度 50-100 字（中文字符计数）

#### Scenario: 单款商品理由短
- **WHEN** Agent 输出推荐文案
- **THEN** 每款商品的描述句（"商品名 + 卖点"部分）≤ 25 字（含商品名约 10-15 字 + 卖点 ≤ 10 字 + 标点）

#### Scenario: 仍保留情感开场
- **WHEN** sessionMood = hesitant，Agent 输出
- **THEN** 文本开头带 hesitant 风格的 1-2 个关键词（如"别纠结"/"直接给你挑"）

#### Scenario: 仍引用 userNeeds 关键词
- **WHEN** userNeeds = "category=跑鞋,scenario=橡胶跑道,budget=1000"
- **THEN** 输出文本中引用"跑鞋"或"橡胶跑道"或"1000"等 1-2 个关键词

#### Scenario: 收尾问句仍保留
- **WHEN** Agent 输出
- **THEN** 文本末尾包含一个带选择的疑问句（如"哪款？"/"前两款里选一个？"）

### Requirement: emotion agent 保留跨轮记忆

EmotionAgent SHALL 保留 InMemoryMemory 跨轮累积，不在每次 `wrap()` 或 `streamWrap()` 调用前清空，使 Agent 能承接上一轮 speechText。

#### Scenario: 跨轮不清空
- **WHEN** 同一 session 连续两次调用 EmotionService.wrap 或 EmotionStreamingService.streamWrap
- **THEN** 第二次调用前不执行 `agent.getMemory().clear()`，memory 中保留上一轮对话内容

### Requirement: emotion.txt 不删除作为回滚资产

`prompts/emotion.txt`（旧版独立 EmotionAgent prompt）SHALL 保留在仓库中，**不在本期清理**。理由：
- 上版本说明保留作回滚资产
- emotion-merged.txt 如果出现质量问题，可以快速切换 `EmotionAgentBuilder.PROMPT_FILE` 回 `"emotion.txt"`

#### Scenario: emotion.txt 文件存在
- **WHEN** 检查 `voice-shopping-ai/src/main/resources/prompts/`
- **THEN** 同时存在 `emotion.txt` 和 `emotion-merged.txt`
