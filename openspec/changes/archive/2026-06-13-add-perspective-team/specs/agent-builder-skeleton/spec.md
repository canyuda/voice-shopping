## MODIFIED Requirements

### Requirement: Agent Builder 类结构

系统 SHALL 在 `com.voiceshopping.ai.agent` 包下建立 5 个子包的 Agent Builder 类，每个类提供 `build()` 方法返回 `ReActAgent`。

| Builder | 状态 |
|---------|------|
| `agent.intent.IntentAgentBuilder` | ✅ 已实现（qwen-turbo + InMemoryMemory + intent.txt） |
| `agent.clarify.ClarifyAgentBuilder` | ✅ 已实现（qwen-turbo + InMemoryMemory + clarify.txt） |
| `agent.rec.RecAgentBuilder` | ✅ 已实现（qwen-max + InMemoryMemory + rec.txt） |
| `agent.emotion.EmotionAgentBuilder` | ✅ 已实现（qwen-max + InMemoryMemory + emotion.txt） |
| `agent.perspective.PerspectiveAgentBuilder` | ✅ 已实现（qwen-plus via `multiAgentChatModel` + InMemoryMemory + 调用方传入的 sysPrompt） |

`PerspectiveAgentBuilder` 与其它 4 个 builder 的差异：
- 其它 4 个 builder 内部 `promptLoader.load(<固定文件>)`，`build()` 无参。
- `PerspectiveAgentBuilder.build(String name, String sysPrompt)` 接受**已加载好的 prompt 字符串**，由调用方（`AgentFactory.newPerspectiveTeam`）决定加载哪个 prompt 文件。
- `PerspectiveAgentBuilder` MUST NOT 直接持有 `PromptLoader`。
- `PerspectiveAgentBuilder` MUST 通过 `@Qualifier("multiAgentChatModel")` 注入 chat model（其它 builder 用 `mainChatModel` 或 `lightChatModel`）。
- 每次 `build` 调用 MUST 返回**全新的** `InMemoryMemory` 实例。
- 方法 MUST NOT 返回 `null`。

#### Scenario: 文件存在性检查
- **WHEN** 查看 `voice-shopping-ai/src/main/java/com/voiceshopping/ai/agent/` 目录
- **THEN** 每个子包目录存在，且包含对应的 Builder 类文件

#### Scenario: PerspectiveAgentBuilder 返回真实 ReActAgent
- **WHEN** 调用 `perspectiveBuilder.build("price_advisor", "<some prompt>")`
- **THEN** 返回非 null 的 `ReActAgent`，其 name 为 `"price_advisor"`，model 为 `multiAgentChatModel` Bean，memory 为新建的 `InMemoryMemory`

### Requirement: Prompt 模板文件

系统 SHALL 在 `voice-shopping-ai/src/main/resources/prompts/` 目录下维护 Prompt 模板文件。

| 文件 | 状态 |
|------|------|
| `intent.txt` | ✅ 已填充（意图识别 prompt） |
| `clarify.txt` | ✅ 已填充（澄清追问 prompt） |
| `rec.txt` | ✅ 已填充（推荐理由生成 prompt） |
| `emotion.txt` | ✅ 已填充（口语包装 prompt） |
| `perspective/perspective_price.txt` | ✅ 已填充（价格顾问角色） |
| `perspective/perspective_pro.txt` | ✅ 已填充（专业跑者角色） |
| `perspective/perspective_beginner.txt` | ✅ 已填充（入门买家角色） |

三个 perspective 文件的具体文本契约见 `perspective-team` 能力 spec 中「三个角色 Prompt 文件填充」需求。所有 prompt 文件 MUST NOT 包含 `# TODO` 占位行。

#### Scenario: 文件存在性检查
- **WHEN** 查看 `voice-shopping-ai/src/main/resources/prompts/` 目录
- **THEN** 上述文件全部存在

#### Scenario: 无 TODO 占位
- **WHEN** 任意 prompt 文件被 `PromptLoader.load` 读取
- **THEN** 返回的字符串非空且不含子串 `TODO`
