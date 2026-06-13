## Context

voice-shopping-ai 模块当前仅有 ASR/TTS 服务和配置类（`config/`、`asr/`、`tts/`、`model/`），缺少 Agent 层的统一基础设施。根据项目 Agent 架构设计（4 个 Worker Agent + 1 个 Orchestrator 状态机 + MsgHub 旁路点评团），需要在 `agent/` 包下搭建 Agent 实例管理和 Prompt 加载的基础能力。

## Goals / Non-Goals

**Goals:**
- `AgentFactory` 提供线程安全的 Agent 实例缓存，支持 LRU 淘汰和主动清理
- `PromptLoader` 提供统一的 classpath Prompt 文件加载能力
- 建立 Agent Builder 的包结构和类签名骨架
- 建立 Prompt 模板文件的目录和占位文件

**Non-Goals:**
- 不实现任何 Agent Builder 的具体逻辑（仅占位）
- 不编写 Prompt 模板内容（仅占位文件）
- 不集成到 Orchestrator 状态机（后续版本）
- 不涉及 WebSocket 或 Controller 层的改动

## Decisions

### 1. AgentFactory 使用 LinkedHashMap 实现 LRU

选择 `LinkedHashMap(16, 0.75f, true)` + `removeEldestEntry` 方案，而非 Guava Cache 或 Caffeine：
- Spring Boot 已内置，零额外依赖
- `accessOrder=true` 使得每次 get 都会将条目移到链表尾部，天然实现 LRU
- `removeEldestEntry` 在超过 1000 条时自动淘汰最久未访问的条目
- 语音导购场景下 1000 个活跃会话足够覆盖峰值并发

被淘汰的条目依赖 GC 回收 Agent 及其内部的 InMemoryMemory。

### 2. 线程安全使用 Collections.synchronizedMap

使用 `Collections.synchronizedMap` 包裹 LinkedHashMap，而非 ConcurrentHashMap：
- LRU 语义需要有序 Map，ConcurrentHashMap 不维护顺序
- 语音导购并发量级（几百到上千会话）下，synchronizedMap 的锁粒度足够
- 实现简单，可读性高

### 3. 两条命名方法区分缓存语义

- `get(sessionId)` — 获取或创建主链路 4 个 Agent（intent/clarify/rec/sentiment）的 AgentSet，使用 LRU 缓存
- `newPerspectiveTeam()` — 每次调用新建点评团实例（price/pro/beginner 三个视角），不走缓存

点评团是旁路异步任务，每次分析都是独立的上下文，不应复用 InMemoryMemory 中的历史消息。

### 4. PromptLoader 使用 Spring ClassPathResource

遵循用户提供的参考实现，使用 `org.springframework.core.io.ClassPathResource` 读取 classpath 下的 prompt 文件。异常转换为 `RuntimeException` 遵循 fail-fast 原则。

### 5. Agent Builder 占位类结构

每个 Builder 定义为 package-private 类，位于对应子包下：
- `agent.intent.IntentAgentBuilder`
- `agent.clarify.ClarifyAgentBuilder`
- `agent.rec.RecAgentBuilder`
- `agent.sentiment.SentimentAgentBuilder`
- `agent.perspective.PerspectiveAgentBuilder`

每个类包含 `// TODO: implement in subsequent version` 注释，无方法体。

## Risks / Trade-offs

- **LRU 淘汰导致 Agent 重建成本**：被淘汰后下次访问需重新创建 4 个 Agent 实例 → 1000 条容量远超活跃会话数，淘汰概率极低
- **synchronizedMap 在极高并发下可能成为瓶颈** → 当前阶段并发量不足以触发，后续可升级为 ReadWriteLock 或 Caffeine
- **InMemoryMemory 内存占用**：每个 Agent 持有对话历史，需确保会话结束时调用 `remove(sessionId)` → 在会话管理模块（已实现）关闭会话时调用此方法
