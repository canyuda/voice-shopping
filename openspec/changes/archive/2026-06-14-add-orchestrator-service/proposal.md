## Why

目前 `IntentService / ClarifyService / ParallelRecommendService / EmotionService / PerspectiveHubService` 已各自落地，但缺少把它们按意图分支编排起来的总线层 —— 没有 OrchestratorService，WebSocket / Controller 想跑完整对话链路就要逐个拼装 Service，调用方与状态机耦合，session_state 的 phase / slots / last_recommendations 维护散落各处，PRODUCT_COMPARE 这种"基于上一轮重排"的语义也无处实现。本次新增 OrchestratorService 把链路收口为单一方法 `EmotionResult handle(String sessionId, Long userId, String utterance)`，并补齐 session_state 全字段的及时更新。

同时校准三处文档 / 注释中关于 session 状态与渠道的口径错误（phase / session.channel / session.outcome 的合法取值范围），避免下游同事按错误注释做决策。

## What Changes

- **新增 OrchestratorService**：业务编排层，内部组合既有 Service 完成 ASR 文本 → EmotionResult 的全链路，对外只暴露 `handle(sessionId, userId, utterance)` 一个方法。
- **新增意图兜底矫正**：① priceDirection + 上轮已有推荐 → 强制改写为 `PRODUCT_COMPARE`；② category + (budget|scenario|brand) 任一齐全 → 强制改写为 `PRODUCT_RECOMMENDATION`（复用 `ClarifyRuleService.missingSlots`）。
- **新增 PRODUCT_COMPARE 重排语义**：基于 `session_state.last_recommendations` 的实际价格区间设新过滤条件 —— cheaper 改写 `budget = maxPrice * 0.8`；expensive 写入独立字段 `priceMin = minPrice * 1.2`（不污染 budget），同时 `excludeProductIds = lastIds` 避免重复推同款。
- **新增 last_recommendations 的结构契约**：JSONB 字段以专用 record `LastRecommendationsSnapshot { items, minPrice, maxPrice, productIds }` 序列化，替代裸 `Map<String, Object>`。
- **新增 perspective 开关**：`voice-shopping.perspective.enabled`（默认 false），仅在 PRODUCT_RECOMMENDATION 分支、商品推荐之后、情感应答之前生效，把点评意见拼到 utterance 末尾再交给 EmotionService。
- **新增 Timer 监控**：MeterRegistry 度量 `voice.shopping.orchestrator.handle` 全流程耗时，business 模块新增 `micrometer-core` 依赖。
- **新增 ensureCompliant 占位**：合规兜底空壳，仅打印日志后透传 EmotionResult，留给后续合规改造接入。
- **修订 session-management capability**：把 phase / session.channel / session.outcome 的合法取值显式写入 spec 与实体注释。
  - phase: `INTENT / CLARIFY / RECOMMEND / ORDER_CONFIRM / ENDED`
  - session.channel: `HOME_ENTRY / PRODUCT_PAGE / SEARCH_FALLBACK`
  - session.outcome: `ORDERED / ABANDONED / FOLLOWUP`

## Capabilities

### New Capabilities
- `orchestrator-service`: 对话总线编排能力 —— 单一入口 handle 串联意图理解、需求澄清、商品推荐、情感应答、点评团（可选）、合规兜底，并维护 session_state 全字段。

### Modified Capabilities
- `session-management`: 显式约束 phase / session.channel / session.outcome 三个字段的合法取值集合，并要求实体注释与之对齐。
- `rec-orchestration`: 不变更 `recommend(...)` 现有契约，仅新增 PRODUCT_COMPARE 场景下调用方可写入的两个 slot 字段语义（`priceMin`、`excludeProductIds`），由调用方负责注入，retriever 是否消费这两个字段由本次 tasks 决定。

## Impact

- **新增模块代码**
  - `voice-shopping-business/src/main/java/com/voiceshopping/business/orchestrator/OrchestratorService.java`
  - `voice-shopping-common/src/main/java/com/voiceshopping/common/dto/agent/LastRecommendationsSnapshot.java`
- **修改 pom**
  - `voice-shopping-business/pom.xml` 新增 `io.micrometer:micrometer-core`
- **修改既有代码**
  - `voice-shopping-infrastructure` 中 `Session` / `SessionState` 实体注释补全合法取值
  - `voice-shopping-business/rec/RecommendCandidateRetriever`：消费 `priceMin` / `excludeProductIds` 两个 slot（如本次纳入）
  - `voice-shopping-infrastructure/vector/SqlFilterBuilder`：相应补 `priceMin` / `excludeProductIds` 过滤分支（如本次纳入）
- **修改文档**
  - `CLAUDE.md` 中 phase 状态机示意 → 改写为新的取值集合
  - `docs/data/table-specifications.md` 中 session.channel / session.outcome / session_state.phase 取值
- **配置**
  - 新增 `voice-shopping.perspective.enabled`（默认 false）
- **不在范围**
  - ORDER_CONFIRM 真实下单（仅占位）
  - ensureCompliant 真实合规校验（仅占位）
  - 改 IntentAgent prompt 让其稳定输出 `priceDirection` 槽位
  - 改 EmotionAgent prompt 让其感知 chitchat 模式
- **依赖**
  - 调用方需保证 session 已通过 `SessionService.getOrCreate` 提前开好，否则 handle 抛 `NotFoundException` fail fast
