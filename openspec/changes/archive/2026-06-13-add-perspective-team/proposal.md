## Why

主链路 `RecommendOrchestrator.recommend` 串行执行「画像加载 → 召回 → 重排 → 理由生成」，其中前两步互不依赖却串行，约 200ms 可被压缩。同时，推荐结果只给出"商品+理由"，缺少多视角讨论氛围——价格、专业、入门三种买家关心点用户单看一段 reason 难以形成判断。本次改动通过两条独立但互补的路径解决：

1. **旁路点评团**：在主链路给出 Top-K 后，并行启动一组三角色 ReActAgent（价格顾问 / 专业跑者 / 入门买家）在同一 `MsgHub` 内**顺序发言、自动广播**，形成有讨论感的点评文案，失败降级为空字符串、不阻塞主链路。
2. **并行合流**：`profile.load` 与「召回链（embed→filter→retrieveWithFallback）」用 `CompletableFuture` 真并行，再合流交给 `rerank`。
3. **事件总线**：把"用户开口说话"这种与主链路无关的横切信号（预热缓存、内容审计）改用 Spring `ApplicationEvent` + `@Async @EventListener` 解耦。

旁路设计的根因：主链路要求确定性，状态机每一步结果可预测；点评团是锦上添花，3 次 LLM 调用约 1.2-1.8s，串在主链路里会割裂语音体验。

## What Changes

- **新增 `perspective-team` 能力**：基于 `io.agentscope.core.pipeline.MsgHub` 实现三角色顺序广播点评。
  - 新增 `MultiAgentModelConfig`：注册 `multiAgentChatModel` Bean（qwen-plus + `DashScopeMultiAgentFormatter`），apiKey 复用 `${dashscope.api-key}`。
  - 新增 `PerspectiveHubService.discuss(sessionId, utterance, items) → String`：用 try-with-resources 管理 `MsgHub`，依次无参 `call().block()` 收集三段发言并按固定格式拼接，异常降级为空串。
  - 新增 `PerspectiveHubController` (`POST /api/v1/hub/perspective`)：先调主链路推荐，再调点评，返回 `{recommendation, perspectiveText}`。
  - 填充三个角色 prompt：`prompts/perspective/perspective_price.txt` / `_pro.txt` / `_beginner.txt`（当前为 `# TODO` 占位）。
- **新增 `voice-event-bus` 能力**：解耦"用户开口"横切信号。
  - 新增 `UserSpokenEvent` record。
  - 新增 `VoiceEventPublisher`（封装 `ApplicationEventPublisher.publishEvent`）。
  - 新增 `VoiceEventListeners`：`@Async @EventListener` 实现 `onUserSpokenWarmup` 与 `onUserSpokenAudit` 两个监听器。
  - 新增 `EventDebugController` (`POST /api/v1/event/user-spoken`)：触发事件用于联调。
- **修改 `agent-builder-skeleton` 能力**：补全 `PerspectiveAgentBuilder.build(name, sysPrompt)`，按 `mainChatModel`/`lightChatModel` 同款样板挂 `multiAgentChatModel` + `InMemoryMemory`，移除 `// TODO` 占位返回 null 的实现。
- **修改 `rec-orchestration` 能力**：新增 `ParallelRecommendService` 作为可并行执行的实现，**复用** `RecommendOrchestrator` 现有的 `retrieveWithFallback` 两级 fallback 语义（预算 +30% / 去 categoryL2），不降级为单次 `fetchCandidates`；用 `CompletableFuture.supplyAsync` 并行 `profile.load(userId)` 与「embed+filter+retrieveWithFallback」两腿，`thenCombine` 合流后接 `rerank → top K → reasons`。**`RecommendOrchestrator` 类不改**，维持二者并存。

## Capabilities

### New Capabilities

- `perspective-team`: 旁路三角色 `MsgHub` 点评团能力。覆盖 `MultiAgentModelConfig` Bean、`PerspectiveAgentBuilder` 完整实现、`PerspectiveHubService.discuss` 协议、prompt 文件契约、HTTP 调试接口、降级语义。
- `voice-event-bus`: 基于 Spring `ApplicationEvent` 的横切信号广播能力。覆盖 `UserSpokenEvent` payload、`VoiceEventPublisher` 发布约定、`@Async` 监听器约定、HTTP 调试接口。

### Modified Capabilities

- `rec-orchestration`: 新增 `ParallelRecommendService` 作为并行实现，必须保留与 `RecommendOrchestrator` 等价的 fallback 语义；并行只发生在 profile 加载与召回链之间，rerank/reasons 依旧串行。
- `agent-builder-skeleton`: `PerspectiveAgentBuilder.build(name, sysPrompt)` 必须返回真实可用的 `ReActAgent`，而非 `null`。

## Impact

- **代码新增**（10 个文件）：`MultiAgentModelConfig`、`PerspectiveHubService`、`PerspectiveHubController`、`UserSpokenEvent`、`VoiceEventPublisher`、`VoiceEventListeners`、`EventDebugController`、`ParallelRecommendService`，加上 3 个 prompt 文件填充。
- **代码修改**（1 个文件）：`PerspectiveAgentBuilder.build` 由占位实现改为真实实现。
- **不改动**：`AgentFactory`（`newPerspectiveTeam()` 已就绪）、`RecommendOrchestrator`（保持向后兼容）、Flyway 脚本（无 schema 变更）、Redis Key 规范（无新 key）。
- **配置**：复用 `${dashscope.api-key}`；新增可选 `${dashscope.model.multi-agent:qwen-plus}` 参数。
- **依赖**：无新依赖（`MsgHub` / `DashScopeMultiAgentFormatter` 由 `io.agentscope:agentscope:1.0.11` 提供，已在 BOM 中）。
- **运行时**：旁路点评 +1.2-1.8s（三次串行 LLM），通过与情感 Agent 并行抵消；事件监听走 `taskExecutor`（已由 `@EnableAsync` 启用）。
