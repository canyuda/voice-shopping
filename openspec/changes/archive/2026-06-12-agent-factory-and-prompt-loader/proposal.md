## Why

语音导购系统需要在 voice-shopping-ai 模块中建立 Agent 基础设施层。当前模块仅有 ASR/TTS 服务和配置类，缺少 Agent 实例管理、Prompt 加载和 Agent Builder 骨架，无法支撑多智能体编排的后续实现。

## What Changes

- 新增 `AgentFactory` — 基于 LRU 的 Agent 实例工厂，按 sessionId 管理 Agent 集合的生命周期，支持线程安全的多用户并发访问
- 新增 `PromptLoader` — 从 classpath 加载 Prompt 模板文件的工具组件
- 新增 5 个 Agent Builder 占位类（intent/clarify/rec/sentiment/perspective），定义包结构和类签名，具体实现留待后续版本
- 新增 7 个 Prompt 模板占位文件（intent/clarify/rec/sentiment + 3 个 perspective 视角），内容留待后续版本填充

## Capabilities

### New Capabilities

- `agent-factory`: LRU 缓存的 Agent 工厂，按 sessionId 管理主链路 Agent 集合，支持会话结束时主动清理，线程安全
- `prompt-loader`: 从 classpath 加载 Prompt 模板文件，统一管理 Prompt 文本的读取
- `agent-builder-skeleton`: 5 个 Agent Builder 和 7 个 Prompt 模板的占位骨架，建立包结构和类/文件签名，具体实现标记 TODO

### Modified Capabilities

<!-- No existing capabilities have spec-level requirement changes -->

## Impact

- 影响模块：`voice-shopping-ai`（新增 `agent/` 包及子包、`resources/prompts/` 目录）
- 新增依赖：AgentScope Java SDK（Agent、ReActAgent、InMemoryMemory 等），Spring Core（`@Component`、`ClassPathResource`）
- 不影响现有 API 和业务逻辑
