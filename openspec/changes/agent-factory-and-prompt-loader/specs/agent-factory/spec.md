## ADDED Requirements

### Requirement: AgentFactory 按 sessionId 管理 Agent 实例缓存

系统 SHALL 提供 `AgentFactory` 组件，使用 LRU 策略按 sessionId 缓存主链路 Agent 集合（AgentSet），最大容量为 1000 个活跃会话。

AgentSet SHALL 包含 4 个 Agent 实例：
- IntentAgent（意图理解）
- ClarifyAgent（需求澄清）
- RecAgent（商品推荐）
- SentimentAgent（情感应答）

AgentFactory SHALL 支持会话结束时主动移除缓存条目，释放 Agent 持有的 InMemoryMemory 内存。

#### Scenario: 首次获取会话 AgentSet
- **WHEN** 调用 `get(sessionId)` 且该 sessionId 不在 LRU 缓存中
- **THEN** 创建新的 AgentSet（包含 4 个 Agent 实例），存入 LRU 缓存，返回该 AgentSet

#### Scenario: 重复获取同一会话 AgentSet
- **WHEN** 再次调用 `get(sessionId)` 且该 sessionId 已在缓存中
- **THEN** 直接从缓存返回已有 AgentSet，不创建新实例

#### Scenario: LRU 淘汰
- **WHEN** 缓存条目数超过 1000 且存在最久未访问的条目
- **THEN** 自动淘汰该条目，由 GC 回收其 Agent 实例及 InMemoryMemory

#### Scenario: 会话结束主动清理
- **WHEN** 调用 `remove(sessionId)`
- **THEN** 从缓存中移除该 sessionId 对应的 AgentSet，后续对该 sessionId 的 get 调用重新创建 AgentSet

### Requirement: AgentFactory 线程安全

AgentFactory SHALL 在多线程并发访问下保证缓存操作的线程安全。

#### Scenario: 多用户并发访问
- **WHEN** 多个线程同时调用 `get()` 或 `remove()` 方法
- **THEN** 缓存操作不会出现数据竞争或不一致状态

### Requirement: newPerspectiveTeam 每次都新建实例

AgentFactory SHALL 提供 `newPerspectiveTeam()` 方法，每次调用创建全新的 PerspectiveAgent 集合（price/pro/beginner 三个视角），不使用缓存。

#### Scenario: 创建点评团队
- **WHEN** 调用 `newPerspectiveTeam()`
- **THEN** 返回全新的 3 个 PerspectiveAgent 实例，不查缓存也不写入缓存
