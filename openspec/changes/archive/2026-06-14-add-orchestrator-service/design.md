## Context

`voice-shopping-business` 模块下已落地完整的 Worker Service 集合：`IntentService` / `ClarifyService` / `ParallelRecommendService` / `EmotionService` / `PerspectiveHubService` / `SessionStateService` / `SessionService` / `ShortTermMemory` / `VoiceEventPublisher`。这些原子能力之间缺少协调者，无人维护 session_state 的整体演进，PRODUCT_COMPARE 需要的"基于上一轮重排"能力也没有落地。

本次目标是写一个**薄壳编排层** OrchestratorService —— 不在 Service 内部塞业务规则，只负责按意图把子 Service 串起来 + 维护 session_state 全字段 + 兜底矫正与 fail fast。

## Goals / Non-Goals

**Goals:**

- 单一对外方法 `EmotionResult handle(String sessionId, Long userId, String utterance)` 完整跑通六类意图分支。
- session_state 的 `phase / current_intent / slots / pending_ask / turn_count / last_recommendations` 在每次 handle 调用结束前必须更新为正确值。
- 提供基于上一轮 last_recommendations 的"贵点 / 便宜点"重排能力，且不污染 budget 语义。
- 提供 `voice-shopping.perspective.enabled` 开关控制点评团是否参与 PRODUCT_RECOMMENDATION。
- 全流程 Timer 监控，business 模块只引 `micrometer-core`（registry 实现仍然由 web 模块持有）。
- 同步校准 phase / session.channel / session.outcome 三个字段在文档与实体注释中的合法取值。

**Non-Goals:**

- ORDER_CONFIRM 不实现真实下单，仅返回固定话术。
- ensureCompliant 不实现真实合规校验，仅打印日志透传。
- 不修改 IntentAgent / EmotionAgent 的 prompt。
- 不重构 RecommendCandidateRetriever 现有过滤策略 —— `priceMin` / `excludeProductIds` 是否生效见决策 D5。
- Orchestrator 不负责 `getOrCreate` session（由 web 层提前完成）。

## Decisions

### D1. handle 不开 session，只 find；缺 session 抛 NotFoundException

**决定**：`OrchestratorService.handle` 内部调用 `sessionRepository.findById(UUID.fromString(sessionId))`，缺则抛 `NotFoundException("会话不存在: " + sessionId)`。

**理由**：

- 现有 `SessionService.getOrCreate` 需要四参数 `(sessionId, merchantId, userId, channel)`，本接口只有三参数，没有 merchantId/channel 来源 —— 隐式默认会让多商户隔离失效。
- WebSocket onOpen / Controller 入口点本来就更适合做 session 创建（带 SaToken 上下文 + 渠道信息）。
- Orchestrator 的语义是"编排已就绪的会话"，不是"创建会话"。

**替代方案**：

- handle 加 merchantId/channel 入参 → 改对外签名，破坏"对外只暴露这一个方法"的简洁性。
- 用 SaToken 上下文取 merchantId、写死 channel="VOICE" → 隐式依赖且 channel 写死与 D-Doc 决策矛盾（合法 channel 不含 VOICE）。

### D2. last_recommendations 用专用 record，不再裸 Map

**决定**：在 `voice-shopping-common` 新增

```java
public record LastRecommendationsSnapshot(
    List<RecommendedItem> items,
    BigDecimal minPrice,
    BigDecimal maxPrice,
    List<Long> productIds
) {}
```

序列化为 JSON 后写入 `SessionState.lastRecommendations`（仍是 `Map<String, Object>` 字段，但写入前用 ObjectMapper 转 Map，读出时反序列化为 record）。

**理由**：

- 裸 Map 让消费方（PRODUCT_COMPARE 重排逻辑）需要重复做 null 检查与类型转换。
- record 是不可变值对象，符合项目"Agent DTO 一律 record"的规范。
- 不改实体字段类型，零迁移代价。

### D3. expensive 用独立 priceMin 字段，不污染 budget

**决定**：

| 场景 | slots 写入 |
|------|-----------|
| cheaper | `budget = maxPrice * 0.8` 覆盖原 budget |
| expensive | `priceMin = minPrice * 1.2`，**budget 保持原值**（用户原始上限） |

**理由**：

- budget 语义是"价格上限"。expensive 写下限到 budget 会导致检索层把它当上限，反而推出更便宜的商品。
- 保留 budget 原值能继续约束"用户最多愿意花多少"。

**风险**：本次不强求 retriever 立刻消费 priceMin 字段。即使本次不接入，PRODUCT_COMPARE 至少 `excludeProductIds` 还能保证不复推、cheaper 还能正常工作。priceMin 真正生效见 D5。

### D4. cheaper / expensive 的 priceDirection 取值

**决定**：

- 仅识别 `slots.get("priceDirection")` 取值为 `"cheaper"` 或 `"expensive"`（大小写敏感）。
- 其他取值（包括 null）不触发 D7 矫正规则 ①。

**理由**：

- 本次不改 IntentAgent prompt，不能保证它一定输出哪种值。先用最严格的白名单，其余视作"未识别到方向"。
- 后续可在 IntentAgent prompt 里强约束输出值再放宽这里。

### D5. priceMin / excludeProductIds 是否本次接入到 RecommendCandidateRetriever

**决定**：本次**接入**两个字段：

- `RecommendCandidateRetriever.buildFilter(slots)` 读 `priceMin`、`excludeProductIds`。
- `SqlFilterBuilder` 增加对应分支生成 SQL 片段：`price >= :priceMin` 与 `id NOT IN (:excludeProductIds)`。

**理由**：

- 不接入，PRODUCT_COMPARE 的 expensive 路径就是 dead code，excludeProductIds 也无效，整个 PR 落不了地。
- 既然 spec 要求这次"完整 PRODUCT_COMPARE 重排"，retriever / SqlFilterBuilder 的小改是必要工作，不算"超范围"。

**替代方案**：本次只写 OrchestratorService，不动 retriever → PRODUCT_COMPARE 表现为"和 RECOMMENDATION 几乎一样"，违背意图。

### D6. 信息已足判定复用 ClarifyRuleService.missingSlots

**决定**：D7 矫正规则 ② 的实现：

```java
Map<String, Object> merged = mergeSlots(stateSlots, currentSlots);
if (clarifyRuleService.missingSlots(category, merged).isEmpty()) {
    intent = PRODUCT_RECOMMENDATION;
}
```

**理由**：

- ClarifyRuleService 已经按品类配置了必填槽位（跑鞋必填 scenario 等），是项目唯一的"信息够不够"知识源。
- 用户原始描述"category + (budget|scenario|brand) 任一组合齐全"是它的简化特例。

**替代方案**：硬编码 `category != null && (budget != null || scenario != null || brand != null)` → 与配置层割裂，未来加新品类要改两处。

### D7. 意图兜底矫正算法

**先后顺序**（先优先级高的）：

1. **规则①（priceDirection 锚定）**：上一轮 `last_recommendations` 非空 + 当前 slots 包含合法 priceDirection (`cheaper` / `expensive`) → 不论 LLM 给的什么意图，强制改写为 `PRODUCT_COMPARE`。
2. **规则②（信息已足）**：仅当 LLM 给的是 `CLARIFY_NEEDED` 时触发：合并 state.slots + 当前 slots 后跑 `clarifyRuleService.missingSlots(...)`，若 empty 则强制改写为 `PRODUCT_RECOMMENDATION`。

**为什么规则②只对 CLARIFY 矫正**：LLM 给 PRODUCT_RECOMMENDATION 但实际信息不足，应该走 ClarifyAgent 的内部 ASK；这里不该用"信息已足"反向触碰，避免双向纠错形成震荡。

### D8. CLARIFY_NEEDED 分支：READY 退化到 RECOMMENDATION

**决定**：CLARIFY_NEEDED 分支调用 `ClarifyService.decide`：

- `ASK` → 直接构造 `EmotionResult(question, List.of())` 返回，pending_ask = question，phase = `CLARIFY`。
- `READY` → 退化执行完整 PRODUCT_RECOMMENDATION 链路（含 perspective）。

**理由**：澄清规则可能比 LLM 的意图分类更准（例如用户"再来一双"在没有上轮推荐时 LLM 判 CLARIFY，但规则发现槽位齐全），让 READY 路径直接出推荐避免空转一轮。

### D9. CHITCHAT 走 EmotionService.wrap(空 RecommendResult)

**决定**：`emotionService.wrap(sessionId, utterance, RecommendResult.EMPTY)`。

**风险**：当前 EmotionService 检测到 empty 时 fallback 为"这个条件下合适的不多，要不要放宽点预算再看看？" —— 给闲聊不合适。

**缓解**：作为 Non-Goal 之一，本次不修 EmotionAgent prompt，但 OrchestratorService 在 CHITCHAT 分支拿到 EmotionResult 后**校验 speechText**，若为该兜底文案，则替换为温和的闲聊兜底"不太懂这个，我们聊点你想买啥呗？"。这是临时止血，跟随后续 prompt 改造一起淘汰。

### D10. phase 退出值映射

**决定**：

| 分支结果 | 退出 phase |
|---------|-----------|
| CLARIFY-ASK（追问中） | `CLARIFY` |
| PRODUCT_RECOMMENDATION（含 CLARIFY-READY 退化） | `RECOMMEND` |
| PRODUCT_COMPARE | `RECOMMEND` |
| ORDER_CONFIRM | `ORDER_CONFIRM` |
| CHITCHAT | `INTENT` |
| OUT_OF_SCOPE | `INTENT` |

会话主动结束（用户说"再见"、TTS 完成）由 web 层另行写 `ENDED`，不在 Orchestrator 范围。

### D11. ParallelRecommendService 同时承担 RECOMMEND 与 COMPARE

**决定**：两个分支都注入并调用 `ParallelRecommendService.recommend(...)`。COMPARE 只在调用前修改 slots（写 budget/priceMin/excludeProductIds），其余路径完全一致。

**理由**：复用 retriever / reranker / reasonService 一整条链路，避免"为对比写第二条平行链路"。

### D12. Perspective 开关：纯 Spring `@Value` 注入

**决定**：`@Value("${voice-shopping.perspective.enabled:false}") boolean perspectiveEnabled` 字段注入；分支内 `if (perspectiveEnabled) { perspectiveHubService.discuss(...) }` —— 不抽 `Optional<PerspectiveHubService>`，开关只控制是否调用。

### D13. Timer 监控

**决定**：

- Timer 名 `voice.shopping.orchestrator.handle`
- tag：`intent`（最终矫正后的意图名），`session.id` **不打 tag**（高基数，会爆 Prometheus）。
- 在 `handle` 入口 `Timer.start(meterRegistry)`，finally 块 `sample.stop(timer)`。
- timer 实例缓存（按 intent 缓存 6 个），避免每次 `Timer.builder` 创建。

### D14. 文档校准范围

| 文件 | 改什么 |
|------|--------|
| `CLAUDE.md` | "Orchestrator 状态机"段落改为新取值 `INTENT → CLARIFY → RECOMMEND → ORDER_CONFIRM → ENDED` |
| `docs/data/table-specifications.md` | session.channel / session.outcome / session_state.phase 的合法取值列表 |
| `Session.java` 实体注释 | channel / outcome 字段加 `// allowed: HOME_ENTRY / PRODUCT_PAGE / SEARCH_FALLBACK` 等 |
| `SessionState.java` 实体注释 | phase 字段加 `// allowed: INTENT / CLARIFY / RECOMMEND / ORDER_CONFIRM / ENDED`，默认值由 `"IDLE"` 改为 `"INTENT"` |

**注意**：`SessionState.phase` 当前默认值 `"IDLE"` 不在新合法集合中，本次一并改为 `"INTENT"`，避免新建 session 一进来就违约。但**不写 Flyway 迁移**（DB 字段是 free text varchar，无 CHECK 约束）。

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| 退出 phase 改 `INTENT` 后，老数据库里旧 session 的 `phase = "IDLE"` 仍然存在 | 老数据无害，handle 不读历史 phase 做分支决策；首次写入即覆盖 |
| Perspective 默认关闭，对现有功能无影响；开关打开后增加 ~1.5~3s 串行延迟 | 仅 PRODUCT_RECOMMENDATION 分支生效；用户可配置开关；后续可改为并行（但本次不优化） |
| EmotionAgent 在 CHITCHAT 路径输出推荐式 fallback | D9 中加文案兜底拦截 |
| `LastRecommendationsSnapshot` 反序列化失败（老数据是不同 shape） | 反序列化包 try-catch，失败视为"无上一轮推荐"，PRODUCT_COMPARE 退化为 PRODUCT_RECOMMENDATION |
| priceMin 接入 SqlFilterBuilder 后影响其他分支测试 | 仅在 slots 中显式 put 时触发；retriever 不读则等价于 no-op |
| Timer 高基数风险 | tag 仅 `intent` 一维，最多 6 个值 |
| `clarifyRuleService.missingSlots(category, merged)` 当 category 为 null 时退化到 default 规则 | 默认规则较宽松，可能误判"信息已足"，但只在 LLM 给 CLARIFY 时触发，错误代价是少问一轮 —— 可接受 |

## Migration Plan

1. 先合 D14 文档校准（无代码副作用）。
2. 加 `LastRecommendationsSnapshot` record + business 模块 micrometer-core 依赖。
3. 改 `SqlFilterBuilder` + `RecommendCandidateRetriever` 支持 priceMin / excludeProductIds（带单元测试）。
4. 写 OrchestratorService 主体 + 单测。
5. 加 perspective 配置项默认 false，发布。

回滚：删除 OrchestratorService 即可，其余修改为加法兼容。

## Open Questions

- 后续是否引入 `ENDED` 状态的判定逻辑？目前只是文档定义，未有真实写入路径。
- `priceDirection` 是否后续在 IntentAgent prompt 中明确约束取值？取决于线上 LLM 输出的真实分布。
