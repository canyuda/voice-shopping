## Context

主链路 `RecommendOrchestrator.recommend` 当前为纯串行：
```
profile.load → buildQuery → embed → buildFilter → retrieveWithFallback → rerank → top3 → attachReasons
```
其中 `profile.load` 仅依赖 `userId`，与「embed+filter+retrieveWithFallback」这条只依赖 `utterance+slots` 的链互不依赖，但被串行执行。该串行约 200ms 可压缩。

同时，推荐输出仅含商品+单条 reason，缺少多视角讨论氛围。**`AgentFactory.newPerspectiveTeam()` 与 `PerspectiveTeam` record 已就位**（`voice-shopping-ai/src/main/java/com/voiceshopping/ai/agent/AgentFactory.java:118-136`），但底层 `PerspectiveAgentBuilder.build` 当前**返回 null**（占位 TODO，同文件依赖 `// TODO: implement in subsequent version`），三个 prompt 文件也仅有 `# TODO: fill prompt content` 占位。

AgentScope 1.0.11 实际 API（来自 jar 反编译验证）：
- `MsgHub.builder().name().participants(AgentBase...).announcement(Msg...).enableAutoBroadcast(boolean).build()`，`MsgHub implements AutoCloseable`，`enter() : Mono<MsgHub>`，try-with-resources 出块自动 `close()`。
- `Msg.builder().name().role(MsgRole).textContent().build()`；`msg.getTextContent()` 取文本。
- `CallableAgent` 接口提供 **`Mono<Msg> call()` 无参 default 方法**（决策 2 的地基）。
- `DashScopeChatModel.builder()` 接受 `.formatter(Formatter<...>)`；`DashScopeMultiAgentFormatter` 是该 Formatter 泛型基类的实现。
- `@EnableAsync` 已在 `voice-shopping-web/.../VoiceShoppingApplication.java` 启用。
- `mainChatModel` / `lightChatModel` Bean 在 `voice-shopping-ai/.../ai/model/AgentScopeConfig.java` 定义，apiKey 来自 `@Value("${dashscope.api-key}")`。

调用样板已存在：`RecommendReasonService:55` 用 `agent.call(Msg).block()` 获取 `Msg`，`getTextContent()` 取文本，本次 `discuss` 与之同构（仅触发参数从 `Msg` 改为无参）。

## Goals / Non-Goals

**Goals:**
- 落地三角色 `MsgHub` 旁路点评，三个 agent 顺序发言，**后发言者能看到前发言**（依赖 auto-broadcast）。
- `discuss` 整体异常降级为空字符串，**绝不阻塞主链路**。
- `ParallelRecommendService` 输出与 `RecommendOrchestrator.recommend` **结果等价**（含 fallback 兜底语义），仅时延更短。
- 横切信号（用户开口）通过 Spring `ApplicationEvent` 解耦，监听器异步执行不阻塞 publish。
- Bean、配置、apiKey 全部走配置文件注入，**不引入硬编码**。

**Non-Goals:**
- 不修改 `RecommendOrchestrator` 类（保留向后兼容；`ParallelRecommendService` 与之并存）。
- 不缓存 `PerspectiveTeam`（`AgentFactory.newPerspectiveTeam()` 每次新建、用完即抛，符合现有约定）。
- 不引入新的 Redis Key、新的数据库表/字段、新的 Maven 依赖。
- 不实现 perspective 结果落库或链路追踪（旁路是"锦上添花"，可观测性后续迭代）。
- 不改造主链路推荐为流式输出。

## Decisions

### D1: `MultiAgentModelConfig` 沿用 `AgentScopeConfig` 注入样板，模型默认 qwen-plus
**决策**：新增 `voice-shopping-ai/.../ai/model/MultiAgentModelConfig.java`，注册 `multiAgentChatModel` Bean。
- apiKey: `@Value("${dashscope.api-key}")`，与 `AgentScopeConfig` **同源**。
- 模型名：`@Value("${dashscope.model.multi-agent:qwen-plus}")`，可配置可覆盖。
- formatter：`new DashScopeMultiAgentFormatter()`（无参构造器，已验证存在）。
- `DashScopeChatModel.builder().apiKey(...).modelName(...).formatter(formatter).build()`。

**为什么不放进 `AgentScopeConfig`**：保持单一职责，主链路 / 多 agent 配置职责分离；将来若多 agent 模型策略演化（不同温度、不同 baseUrl），独立 config 类边界更清晰。

### D2: `PerspectiveAgentBuilder.build` 与现有 4 个 builder 同款样板
**决策**：

```java
@Component
public class PerspectiveAgentBuilder {
    private final DashScopeChatModel model;
    public PerspectiveAgentBuilder(@Qualifier("multiAgentChatModel") DashScopeChatModel model) {
        this.model = model;
    }
    public ReActAgent build(String name, String sysPrompt) {
        return ReActAgent.builder()
                .name(name)
                .sysPrompt(sysPrompt)
                .model(model)
                .memory(new InMemoryMemory())
                .build();
    }
}
```

**注意**：`PromptLoader` 由 `AgentFactory.newPerspectiveTeam` 持有，不在 builder 内注入；builder 只接受**已加载好的字符串**，与 `IntentAgentBuilder` 等区别在此（其它 builder 自己 load 固定 prompt 文件，perspective 由调用方决定 prompt）。

### D3: `discuss` 用 try-with-resources + 顺序无参 `call().block()`
**决策**：

```java
public String discuss(String sessionId, String utterance, List<RecommendedItem> items) {
    if (items == null || items.isEmpty()) return "";
    try {
        PerspectiveTeam team = agentFactory.newPerspectiveTeam();
        Msg announcement = Msg.builder()
                .name("host")
                .role(MsgRole.USER)
                .textContent(formatAnnouncement(utterance, items))
                .build();

        try (MsgHub hub = MsgHub.builder()
                .name("perspective_" + sessionId)
                .participants(team.priceAgent(), team.proAgent(), team.beginnerAgent())
                .announcement(announcement)
                .enableAutoBroadcast(true)
                .build()) {

            hub.enter().block();

            // No-arg call() — announcement is already in each agent's memory.
            // Auto-broadcast feeds prior speeches into subsequent agents' memory.
            Msg priceMsg = team.priceAgent().call().block();
            Msg proMsg = team.proAgent().call().block();
            Msg beginnerMsg = team.beginnerAgent().call().block();

            return String.format("价格顾问：%s%n专业用户：%s%n入门买家：%s",
                    safeText(priceMsg), safeText(proMsg), safeText(beginnerMsg));
        }
    } catch (Exception e) {
        log.warn("Perspective discuss failed, degrading to empty: {}", e.getMessage());
        return "";
    }
}
```

**announcement 文本格式**（用户决策原文，不改）：
```
用户原话：<utterance>

待点评的 Top3 商品：
- <name> / <price> / <reason>
- ...

请各位依次发言，每人 30 字以内。
```
价格用 `BigDecimal.toPlainString()` 拼接，避免科学计数法。

**`AgentSet` 名字小修正**：proposal/design 用 `proAgent / beginnerAgent`，与 `AgentFactory.PerspectiveTeam` record 字段（`priceAgent, proAgent, beginnerAgent`）严格一致。

### D4: 拼接前缀对齐——「专业用户」（不是 prompt 里的「专业跑者」）
**决策**：用户决策原文规定输出格式为 `专业用户:%s`，而 prompt 里的 agent 内部身份是「专业跑者」。这是输出层 vs 角色定义的区分——保持用户决策的输出文案。Prompt 内部仍写「专业跑者」以引导风格。

### D5: 异常降级**仅在 `discuss` 内**捕获
**决策**：`discuss` 内部 `try { ... } catch (Exception e)` 整体兜底返回 `""`。Controller 不再额外 try。这避免双重降级、保持异常归因清晰。

### D6: `ParallelRecommendService` 复用 `RecommendOrchestrator` 私有方法 → 重构最小切片
**问题**：`retrieveWithFallback`、`buildFilter`、`buildQuery`、`relaxBudget`、`dropCategoryL2` 在 `RecommendOrchestrator` 中是 `private` 或 package-private。`ParallelRecommendService` 必须复用、不能复制粘贴（否则 fallback 语义会双线漂移）。

**决策**：把召回链相关私有方法**提取到新的 `RecommendCandidateRetriever`**（package-private 协作类，位于 `voice-shopping-business/.../rec/`）。
- `RecommendCandidateRetriever.retrieve(float[] queryVector, Filter filter, Map<String,Object> slots) → List<RecommendedItem>`：内含三级 fallback。
- `RecommendCandidateRetriever.buildQuery(...)` / `buildFilter(...)`：迁移自 Orchestrator。
- `RecommendOrchestrator` 与 `ParallelRecommendService` 都注入并复用它，**两者输出严格等价**（含 fallback）。

**约束**：本次改动**最小限度**修改 `RecommendOrchestrator`——把对内私有方法的调用改为对 `retriever` 的调用，方法签名/外部行为/单元测试不变。Proposal 里"`RecommendOrchestrator` 类不改"的承诺指**外部行为不改**，内部委托属于无副作用重构。

**为什么不直接改 private 为 package-private**：可以，但会让 `RecommendOrchestrator` 同时承担"编排"和"召回兜底"两个责任，长期维护差。提取后单一职责。

### D7: `ParallelRecommendService.recommend` 并行结构
**决策**：

```java
@Service
public class ParallelRecommendService {
    private final UserProfileService profileService;
    private final EmbeddingService embeddingService;
    private final SqlFilterBuilder sqlFilterBuilder;
    private final RecommendCandidateRetriever retriever;
    private final ProfileReranker reranker;
    private final RecommendReasonService reasonService;

    public RecommendResult recommend(String sessionId, Long userId, String utterance, Map<String,Object> slots) {
        CompletableFuture<UserProfileSnapshot> profileF = CompletableFuture.supplyAsync(() ->
                userId != null ? profileService.load(userId) : null);

        CompletableFuture<List<RecommendedItem>> candidatesF = CompletableFuture.supplyAsync(() -> {
            String query = retriever.buildQuery(utterance, slots);
            float[] vec = embeddingService.embed(query);
            Filter filter = retriever.buildFilter(slots);
            return retriever.retrieve(vec, filter, slots);
        });

        var combined = profileF.thenCombine(candidatesF, (profile, candidates) -> {
            if (candidates.isEmpty()) return RecommendResult.EMPTY;
            List<RecommendedItem> reranked = reranker.rerank(candidates, profile, slots);
            List<RecommendedItem> topK = reranked.stream().limit(3).toList();
            String userNeeds = utterance + "; 槽位：" + slots;
            List<RecommendedItem> withReasons = reasonService.attachReasons(sessionId, userNeeds, topK);
            return new RecommendResult(withReasons, "professional");
        });

        try {
            return combined.get();
        } catch (Exception e) {
            throw new IllegalStateException("Parallel recommend failed", e);
        }
    }
}
```

**线程池**：`supplyAsync` 不传 `Executor` 默认走 `ForkJoinPool.commonPool`。本任务以阻塞 IO（DB / 远程 embedding API）为主，建议传入项目自定义 `taskExecutor`（与 `@Async` 共用），避免污染 commonPool。

**fail-fast 边界**：profile 链 / candidates 链任一抛异常，`thenCombine` 立即失败，外层包成 `IllegalStateException`——遵循全局 fail-fast 规范。

### D8: Controller 命名为 `PerspectiveHubController`（非 `RecommendOrchestrator`）
**决策**：用户原文写"实现 `RecommendOrchestrator` 类"是笔误（业务层已有同名 service）。新 Controller 命名为 `PerspectiveHubController`，路径 `POST /api/v1/hub/perspective`。

```java
@RestController
@RequestMapping("/api/v1/hub")
public class PerspectiveHubController {
    private final RecommendOrchestrator recommendOrchestrator;  // 现有的，不改
    private final PerspectiveHubService perspectiveHub;

    @PostMapping("/perspective")
    public ApiResult<PerspectiveHubResp> perspective(@Valid @RequestBody PerspectiveHubReq req) {
        RecommendResult rec = recommendOrchestrator.recommend(
                req.sessionId(), req.userId(), req.utterance(),
                req.slots() != null ? req.slots() : Map.of());
        String text = perspectiveHub.discuss(req.sessionId(), req.utterance(), rec.items());
        return ApiResult.ok(new PerspectiveHubResp(text, rec));
    }
}

public record PerspectiveHubReq(@NotBlank String sessionId, @NotNull Long userId,
                                 @NotBlank String utterance, Map<String,Object> slots) {}
public record PerspectiveHubResp(String perspectiveText, RecommendResult recommendation) {}
```

注入 `RecommendOrchestrator` 而非 `ParallelRecommendService`：调试入口默认走稳定串行版本；并行版本需要单独入口或 feature flag 切换（本次不引入 flag，并行版本自带专属测试入口或在后续 change 中接入）。

**修订**：经评估两者结果等价（D6 保证），改为注入 `ParallelRecommendService` 也合理。最终采用 `RecommendOrchestrator`，理由是**降低本次 change 的责任面**——Hub 接口的回归只需关注 perspective，不需要同时验证并行实现。

### D9: 事件总线 — 监听器走默认 `taskExecutor`
**决策**：

```java
public record UserSpokenEvent(String sessionId, Long userId, String utterance, long timestamp) {}

@Component
public class VoiceEventPublisher {
    private final ApplicationEventPublisher publisher;
    public VoiceEventPublisher(ApplicationEventPublisher publisher) { this.publisher = publisher; }
    public void publish(UserSpokenEvent e) { publisher.publishEvent(e); }
}

@Component
public class VoiceEventListeners {
    private static final Logger log = LoggerFactory.getLogger(VoiceEventListeners.class);

    @Async @EventListener
    public void onUserSpokenWarmup(UserSpokenEvent e) {
        // Best-effort warmup: profile / recent intents / candidate cache.
        // Failures swallowed at listener boundary — they MUST NOT propagate back to publisher.
        log.debug("Warmup for sessionId={} userId={}", e.sessionId(), e.userId());
    }

    @Async @EventListener
    public void onUserSpokenAudit(UserSpokenEvent e) {
        log.info("AUDIT user-spoken sessionId={} userId={} text={}", e.sessionId(), e.userId(), e.utterance());
    }
}
```

**`@Async` 失败语义**：`@Async` 方法内未捕获异常会丢给 `AsyncUncaughtExceptionHandler`（默认仅日志）。本意符合"横切信号失败不影响主链路"。Listener 内部仍**显式不抛业务异常**——`fail fast` 原则适用于**业务校验**链路，不适用于横切预热。

**为什么不用 `@TransactionalEventListener`**：本次没有事务边界。

### D10: Prompt 文件填充与文件名约定
**决策**：覆盖 `voice-shopping-ai/src/main/resources/prompts/perspective/` 下三个文件，内容**严格按用户决策原文**，不缩写、不改语气。文件路径与 `AgentFactory.newPerspectiveTeam` 当前的 `promptLoader.load("perspective/perspective_price.txt")` 等三处调用对齐——已对齐，无需改 factory。

## Risks / Trade-offs

- **[R1] auto-broadcast 时序无强保证** → `priceAgent.call().block()` 返回时，其回复是否**已写入**后续 agent 的 memory？字节码显示 `MsgHub.broadcastToSubscribers` 是 `Mono<Void>`，理论上异步。**缓解**：实测 + 加日志断言（在 design 阶段无法 100% 排除，落地后写一个集成测试验证 pro/beginner 的回复**确实引用**前者）。如时序不可靠，回退方案是手动在每次 `call().block()` 后调 `hub.broadcast(msg).block()`（API 已存在），把广播显式同步化。
- **[R2] `discuss` 三次串行 LLM ≈ 1.2-1.8s** → 旁路设计假设它与情感 Agent **并行**执行（语音生成阶段）。**缓解**：本 change 只交付 `discuss` 自身；与情感 Agent 并行编排在调用方（后续 Orchestrator 状态机改造），不在本次范围。
- **[R3] D6 重构改 `RecommendOrchestrator` 内部** → 与 proposal「不改 RecommendOrchestrator」承诺有解释成本。**缓解**：在 proposal 与 spec 中明确"外部行为不改、内部委托属于无副作用重构"，并配套断言测试（同输入下 `RecommendOrchestrator` 与 `ParallelRecommendService` 输出结果集等价）。
- **[R4] `multiAgentChatModel` 与 `mainChatModel` 同 apiKey 同 baseUrl** → 共享配额、限流叠加。**缓解**：DashScope 用量已支持账号级配额，超限会触发 429；`discuss` 已有整体降级到空串。
- **[R5] `@Async` 监听器异常吞没** → 调试时不易发现。**缓解**：每个 listener 入口/出口加 `log.info`/`log.warn`，并在 `EventDebugController` 文档中说明"异步执行，需查日志"。
- **[R6] `taskExecutor` 容量** → 默认 SimpleAsyncTaskExecutor 不限并发。**缓解**：本 change 不主动配置，沿用项目当前默认；如未来语音并发上升再调优（属于运维范畴）。
- **[R7] 模型 qwen-plus 与主链路 qwen-max 风格差异** → perspective 输出风格可能与主链路 reason 不一致。**缓解**：prompt 已固化 30 字限制和角色调性；如风格漂移再迭代 prompt。

## Migration Plan

无 schema/数据迁移。部署即生效：

1. 部署前确认 `application.yml` 含 `dashscope.api-key`（已有），可选追加 `dashscope.model.multi-agent: qwen-plus`（默认值已在代码中）。
2. 部署后冒烟：
   - `POST /api/v1/agent/recommend`：现有接口仍正常。
   - `POST /api/v1/hub/perspective`：返回 `perspectiveText` 非空且包含三个角色前缀。
   - `POST /api/v1/event/user-spoken`：返回 200 立即；查日志 `VoiceEventListeners` 的 INFO 出现两次（warmup + audit）。
3. 回滚：单纯回退代码即可（无 DB/配置变更）。

## Open Questions

- 是否在后续 change 中把主 Orchestrator 状态机的 `READY_TO_RECOMMEND → GENERATING_SPEECH` 阶段切到 `ParallelRecommendService` + `PerspectiveHubService` 与 `EmotionAgent` 并行？建议本 change 落地稳定后单开。
- `PerspectiveHubController` 是否需要 Sa-Token 鉴权（带 `merchant_id` 隔离）？当前为调试入口，与 `RecommendDebugController` 一致暂不接入；正式接入时一并补全。
- prompt 中"专业跑者"vs 输出前缀"专业用户"是否长期保留这种二元命名？若引发用户困惑，统一为「专业买家」更协调，本次按用户原文。
