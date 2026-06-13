## Purpose

Agent Builder 的包结构和 Prompt 模板文件骨架。IntentAgentBuilder 已完整实现，其余为占位。

## Requirements

### Requirement: Agent Builder 类结构

系统 SHALL 在 `com.voiceshopping.ai.agent` 包下建立 5 个子包的 Agent Builder 类，每个类提供 `build()` 方法返回 `ReActAgent`。

| Builder | 状态 |
|---------|------|
| `agent.intent.IntentAgentBuilder` | ✅ 已实现（qwen-turbo + InMemoryMemory + intent.txt） |
| `agent.clarify.ClarifyAgentBuilder` | TODO — 暂返回 null |
| `agent.rec.RecAgentBuilder` | TODO — 暂返回 null |
| `agent.sentiment.SentimentAgentBuilder` | TODO — 暂返回 null |
| `agent.perspective.PerspectiveAgentBuilder` | TODO — `build(name, sysPrompt)` 暂返回 null |

#### Scenario: 文件存在性检查
- **WHEN** 查看 `voice-shopping-ai/src/main/java/com/voiceshopping/ai/agent/` 目录
- **THEN** 每个子包目录存在，且包含对应的 Builder 类文件

### Requirement: Prompt 模板文件

系统 SHALL 在 `voice-shopping-ai/src/main/resources/prompts/` 目录下维护 Prompt 模板文件。

| 文件 | 状态 |
|------|------|
| `intent.txt` | ✅ 已填充（意图识别 prompt） |
| `clarify.txt` | TODO |
| `rec.txt` | TODO |
| `sentiment.txt` | TODO |
| `perspective/perspective_price.txt` | TODO |
| `perspective/perspective_pro.txt` | TODO |
| `perspective/perspective_beginner.txt` | TODO |

#### Scenario: 文件存在性检查
- **WHEN** 查看 `voice-shopping-ai/src/main/resources/prompts/` 目录
- **THEN** 上述文件全部存在
