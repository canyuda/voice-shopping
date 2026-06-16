## MODIFIED Requirements

### Requirement: emotion-merged.txt 口语包装 Prompt

`prompts/emotion-merged.txt` SHALL 定义合并推荐理由与情感包装的 Prompt，约束 LLM 一步生成口语文本。

**输出长度约束（[CHANGED]）**：
- 整段 **50-100 字**（原为 80-150 字）
- 每款商品理由 **≤ 10 字**（原为"一条最核心的 reason，不展开参数"）
- 砍掉"中底厚实缓震好" → 改为"缓震最好"
- 不展开属性（不写 `drop:5mm` 这类参数）
- 仅保留商品名 + 一句不超过 10 字的核心卖点

Prompt 结构保持不变（**未改**）：
- 角色定义：语音导购的最后一道输出
- 输入字段说明：`userUtterance` / `sessionMood` / `userNeeds` / `products`
- 输出格式：纯文本，禁止 JSON / Markdown / 代码块
- 结构："开场 → 第一款一句话 → 第二款一句话 → 第三款一句话 → 收尾问句"
- 开场规则：按 sessionMood 选风格（neutral/hesitant/impatient/positive/negative）
- 合规红线：禁用绝对化用语等

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

### Requirement: emotion.txt 不删除作为回滚资产

`prompts/emotion.txt`（旧版独立 EmotionAgent prompt）SHALL 保留在仓库中，**不在本期清理**。理由：
- 上版本说明保留作回滚资产
- emotion-merged.txt 如果出现质量问题，可以快速切换 `EmotionAgentBuilder.PROMPT_FILE` 回 `"emotion.txt"`

#### Scenario: emotion.txt 文件存在
- **WHEN** 检查 `voice-shopping-ai/src/main/resources/prompts/`
- **THEN** 同时存在 `emotion.txt` 和 `emotion-merged.txt`
