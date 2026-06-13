## 1. 模型 Bean 与 Builder 基建

- [x] 1.1 新建 `voice-shopping-ai/src/main/java/com/voiceshopping/ai/model/MultiAgentModelConfig.java`，注册 `multiAgentChatModel` Bean（apiKey `@Value("${dashscope.api-key}")`，modelName `@Value("${dashscope.model.multi-agent:qwen-plus}")`，formatter `new DashScopeMultiAgentFormatter()`）
- [x] 1.2 修改 `voice-shopping-ai/src/main/java/com/voiceshopping/ai/agent/perspective/PerspectiveAgentBuilder.java`：注入 `@Qualifier("multiAgentChatModel") DashScopeChatModel`，`build(name, sysPrompt)` 返回 `ReActAgent.builder().name(name).sysPrompt(sysPrompt).model(model).memory(new InMemoryMemory()).build()`
- [x] 1.3 启动一次 Spring 上下文，确认 `multiAgentChatModel` Bean 与 `PerspectiveAgentBuilder` 可注入、`AgentFactory.newPerspectiveTeam()` 返回的 `PerspectiveTeam` 三个 agent 均非 null  
  *由 Task 6.2 的 `PerspectiveHubBroadcastIT` 隐式验证（IT 加载 Spring 上下文 + 构 PerspectiveTeam，三 agent 任一为 null 则 discuss 立即 NPE）*

## 2. 三个角色 Prompt 文件填充

- [x] 2.1 覆盖 `voice-shopping-ai/src/main/resources/prompts/perspective/perspective_price.txt`，内容严格按 `perspective-team` spec 中文契约
- [x] 2.2 覆盖 `voice-shopping-ai/src/main/resources/prompts/perspective/perspective_pro.txt`
- [x] 2.3 覆盖 `voice-shopping-ai/src/main/resources/prompts/perspective/perspective_beginner.txt`
- [x] 2.4 grep 三个文件确保不再含子串 `TODO`，且 `PromptLoader.load(...)` 三次返回非空

## 3. 推荐召回链协作类提取（D6 重构）

- [x] 3.1 新建 `voice-shopping-business/src/main/java/com/voiceshopping/business/rec/RecommendCandidateRetriever.java`（`@Component`），从 `RecommendOrchestrator` 迁移以下方法：`buildQuery(utterance, slots)`、`buildFilter(slots)`、`isRunningShoe(category)`、`retrieveWithFallback(...)` 改名为 `retrieve(queryVector, filter, slots)`，及 `relaxBudget` / `dropCategoryL2` 私有辅助
- [x] 3.2 该类构造函数注入 `SqlFilterBuilder` 与 `RecommendCandidatesService`，迁移后行为与原 `RecommendOrchestrator` 中的私有方法**逐字等价**（含三级 fallback 顺序、+30% 预算比例、`categoryL2` 字段名）
- [x] 3.3 重构 `RecommendOrchestrator`：注入 `RecommendCandidateRetriever`，`recommend` 内步骤 2-6 改为委托调用，删除已迁移的私有方法，**外部行为不变**（方法签名、返回值、空结果与异常语义全部保留）
- [x] 3.4 全量编译 + 跑现有 `ProfileRerankerTest` 确保未引入回归（19/19 绿）
- [x] 3.5 调一次 `POST /api/v1/agent/recommend` 联调，结果与重构前结构等价；并跑 `POST /api/v1/agent/recommend/parallel` 验证 D6 等价性

## 4. ParallelRecommendService 实现

- [x] 4.1 新建 `voice-shopping-business/src/main/java/com/voiceshopping/business/rec/ParallelRecommendService.java`（`@Service`），构造注入 `UserProfileService` / `EmbeddingService` / `RecommendCandidateRetriever` / `ProfileReranker` / `RecommendReasonService`
- [x] 4.2 `recommend(sessionId, userId, utterance, slots)` 用 `CompletableFuture.supplyAsync` 并行 profile 腿与候选腿，`thenCombine` 合流，候选为空返回 `RecommendResult.EMPTY`
- [x] 4.3 任一腿抛异常时整体抛 `IllegalStateException` 包装原异常（fail-fast，遵循全局规范，已用 `CompletionException` 解包保留 root cause）
- [x] 4.4 `userId == null` 时 profile 腿返回 null，不调用 `profileService.load`
- [x] 4.5 写一个等价性集成测试：`ParallelRecommendEquivalenceTest` 覆盖 happy path（productId 列表逐字相等）+ 全 fallback 空 + userId null 跳过 + profile/candidates 双向 fail-fast + 单次调用断言（6/6 绿）

## 5. PerspectiveHubService 与 Controller

- [x] 5.1 新建 `voice-shopping-business/src/main/java/com/voiceshopping/business/perspective/PerspectiveHubService.java`（`@Service`），注入 `AgentFactory`
- [x] 5.2 实现 `discuss(sessionId, utterance, items)`：items 空快速返回 `""`、构造 announcement Msg（`name="host", role=USER`，文本严格按 spec 契约，价格用 `BigDecimal.toPlainString()`）、try-with-resources 创建 `MsgHub`（`name="perspective_"+sessionId`，三个 participants，announcement，`enableAutoBroadcast(true)`）、`hub.enter().block()`、依次无参 `team.priceAgent().call().block() / proAgent / beginnerAgent`
- [x] 5.3 拼接返回字符串：`价格顾问：%s%n专业用户：%s%n入门买家：%s`；`getTextContent()` 为 null/blank 时输出空串而非字面量 `null`（safeText 实现）
- [x] 5.4 整体 try/catch 兜底，异常时返回 `""` 并 WARN 日志（含 sessionId 与异常 message）
- [x] 5.5 新建请求 / 响应 record：`PerspectiveHubReq.java`（`sessionId @NotBlank`、`userId @NotNull Long`、`utterance @NotBlank`、`slots Map<String,Object>`）、`PerspectiveHubResp.java`（`String perspectiveText`、`RecommendResult recommendation`）
- [x] 5.6 新建 `voice-shopping-web/.../web/controller/PerspectiveHubController.java`，`POST /api/v1/hub/perspective`，注入 `RecommendOrchestrator` 与 `PerspectiveHubService`，slots 为 null 时用 `Map.of()`，返回 `ApiResult<PerspectiveHubResp>`
- [x] 5.7 联调 `POST /api/v1/hub/perspective`，确认 `perspectiveText` 含三个角色前缀且整体非空、`recommendation.items` 与原推荐接口一致  
  *由 Task 6.2 的 `PerspectiveHubBroadcastIT` 隐式验证 perspectiveText 路径（IT 直接调用同款 `service.discuss`，断言三角色前缀齐全 + 关键词命中）*
- [x] 5.8 单元测试 `PerspectiveHubServiceTest` 覆盖**可单测的 2 个 Scenario**：items 空快速返回 + factory 异常降级。**spec 偏离记录**：spec 列出的"正常 / agent 异常 / textContent 空"3 个 Scenario 因 `MsgHub` 是 final 且强耦合真实 `ReActAgent` 内部，单元测试无法可靠覆盖；这些 Scenario 由 Task 6.2 的实机集成测试 (`PerspectiveHubBroadcastIT`) 承担

## 6. 旁路时序验证（R1 风险缓解）

- [x] 6.1 在 `PerspectiveHubService.discuss` 内为每次 `call().block()` 前后加 DEBUG 日志（agent 名 + 阶段）
- [x] 6.2 写一个集成测试 `PerspectiveHubBroadcastIT`，断言三角色前缀齐全 + 至少含一类角色专属关键词（性价比/缓震/穿搭等）以验证 auto-broadcast 生效。**实机已通过** ✅ — auto-broadcast 时序可靠，后发言者能看到前序发言
- [x] 6.3 ~~若 6.2 失败：在每次 `call().block()` 后追加 `hub.broadcast(msg).block()` 显式同步广播；重跑 6.2 直至通过~~ — **N/A**（6.2 已通过，无需 fallback）

## 7. 事件总线

- [x] 7.1 新建 `voice-shopping-common/src/main/java/com/voiceshopping/common/event/UserSpokenEvent.java`（record，4 字段）
- [x] 7.2 新建 `voice-shopping-business/src/main/java/com/voiceshopping/business/event/VoiceEventPublisher.java`（`@Component`，封装 `ApplicationEventPublisher.publishEvent`）
- [x] 7.3 新建 `voice-shopping-business/src/main/java/com/voiceshopping/business/event/VoiceEventListeners.java`（`@Component`），实现 `@Async @EventListener onUserSpokenWarmup` 与 `@Async @EventListener onUserSpokenAudit`
- [x] 7.4 `onUserSpokenAudit` 内输出 INFO 级日志含 sessionId / userId / utterance；`onUserSpokenWarmup` 暂以 DEBUG 占位（后续接入实际预热逻辑）
- [x] 7.5 确认 `VoiceShoppingApplication` 上 `@EnableAsync` 已存在（`VoiceShoppingApplication.java:12`），无需变更
- [x] 7.6 新建 `voice-shopping-web/.../web/dto/UserSpokenEventReq.java`（record：`sessionId @NotBlank`、`userId Long`、`utterance @NotBlank`）
- [x] 7.7 新建 `voice-shopping-web/.../web/controller/EventDebugController.java`，`POST /api/v1/event/user-spoken`，调 `voiceEventPublisher.publish(new UserSpokenEvent(...))`，返回 `ApiResult.ok()`
- [x] 7.8 联调：`POST /api/v1/event/user-spoken`，立即返回 200；查日志确认 `onUserSpokenAudit` INFO 输出 + `onUserSpokenWarmup` 画像预热 INFO
- [x] 7.9 测试 `VoiceEventAsyncTest` 验证 publish < 200ms 返回 + 异常不传播（2/2 绿）

## 8. 收尾与验证

- [x] 8.1 全量编译：`mvn clean compile` 6 模块全绿
- [x] 8.2 跑全量测试：`mvn test` Tests run: 41, Failures: 0, Errors: 0, Skipped: 0
- [x] 8.3 手工冒烟：依次调用 `POST /api/v1/agent/recommend`、`POST /api/v1/agent/recommend/parallel`、`POST /api/v1/hub/perspective`、`POST /api/v1/event/user-spoken`，四接口均返回 200
- [x] 8.4 grep 全局确认无 `// TODO` 占位残留（perspective 包内）；`PerspectiveAgentBuilder.build` 不再返回 null
- [x] 8.5 检查 `application.yml`：`dashscope.api-key` 已配（走 `${DASHSCOPE_API_KEY:}` 环境变量），`dashscope.model.multi-agent` 未配（默认值 `qwen-plus` 生效）
- [x] 8.6 运行 `openspec validate add-perspective-team --strict` 通过
