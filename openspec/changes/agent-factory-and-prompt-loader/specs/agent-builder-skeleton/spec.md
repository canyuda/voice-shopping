## ADDED Requirements

### Requirement: Agent Builder 占位类结构

系统 SHALL 在 `com.voiceshopping.ai.agent` 包下建立 5 个子包的 Agent Builder 占位类，每个类包含 `// TODO: implement in subsequent version` 注释。

占位类列表：
- `agent.intent.IntentAgentBuilder` — 意图理解 Agent Builder
- `agent.clarify.ClarifyAgentBuilder` — 需求澄清 Agent Builder
- `agent.rec.RecAgentBuilder` — 商品推荐 Agent Builder
- `agent.sentiment.SentimentAgentBuilder` — 情感应答 Agent Builder
- `agent.perspective.PerspectiveAgentBuilder` — 点评团 Agent Builder

#### Scenario: 文件存在性检查
- **WHEN** 查看 `voice-shopping-ai/src/main/java/com/voiceshopping/ai/agent/` 目录
- **THEN** 每个子包目录存在，且包含对应的 Builder 类文件

#### Scenario: TODO 标记
- **WHEN** 打开任意 Agent Builder 占位类
- **THEN** 文件包含 `// TODO: implement in subsequent version` 注释

### Requirement: Prompt 模板占位文件

系统 SHALL 在 `voice-shopping-ai/src/main/resources/prompts/` 目录下创建 7 个 Prompt 模板占位文件，每个文件包含 `# TODO: fill prompt content` 注释。

占位文件列表：
- `intent.txt` — 意图理解 Prompt
- `clarify.txt` — 需求澄清 Prompt
- `rec.txt` — 商品推荐 Prompt
- `sentiment.txt` — 情感应答 Prompt
- `perspective_price.txt` — 点评团价格视角 Prompt
- `perspective_pro.txt` — 点评团专家视角 Prompt
- `perspective_beginner.txt` — 点评团新手视角 Prompt

#### Scenario: 文件存在性检查
- **WHEN** 查看 `voice-shopping-ai/src/main/resources/prompts/` 目录
- **THEN** 7 个 Prompt 模板文件全部存在

#### Scenario: TODO 标记
- **WHEN** 打开任意 Prompt 模板文件
- **THEN** 文件包含 `# TODO: fill prompt content` 注释
