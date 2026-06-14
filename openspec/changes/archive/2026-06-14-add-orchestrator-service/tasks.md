## 1. 文档与注释校准（先行，零代码风险）

- [x] 1.1 修改 `CLAUDE.md` 中"Orchestrator 状态机"段落的取值为 `INTENT → CLARIFY → RECOMMEND → ORDER_CONFIRM → ENDED`
- [x] 1.2 修改 `docs/data/table-specifications.md` 中 session.channel 取值为 `HOME_ENTRY / PRODUCT_PAGE / SEARCH_FALLBACK`
- [x] 1.3 修改 `docs/data/table-specifications.md` 中 session.outcome 取值为 `ORDERED / ABANDONED / FOLLOWUP`
- [x] 1.4 修改 `docs/data/table-specifications.md` 中 session_state.phase 取值为 `INTENT / CLARIFY / RECOMMEND / ORDER_CONFIRM / ENDED`
- [x] 1.5 在 `Session.java` 实体的 channel 字段加 Javadoc：`allowed: HOME_ENTRY / PRODUCT_PAGE / SEARCH_FALLBACK`
- [x] 1.6 在 `Session.java` 实体的 outcome 字段加 Javadoc：`allowed: ORDERED / ABANDONED / FOLLOWUP；null 表示未结束`
- [x] 1.7 在 `SessionState.java` 实体的 phase 字段加 Javadoc：`allowed: INTENT / CLARIFY / RECOMMEND / ORDER_CONFIRM / ENDED`
- [x] 1.8 把 `SessionState.phase` 默认值从 `"IDLE"` 改为 `"INTENT"`

## 2. 基础设施改造

- [x] 2.1 在 `voice-shopping-business/pom.xml` 增加 `io.micrometer:micrometer-core` 依赖（不引入 prometheus，由 web 模块持有）
- [x] 2.2 在 `voice-shopping-common/src/main/java/com/voiceshopping/common/dto/agent/` 新增 `LastRecommendationsSnapshot.java` record，含 items / minPrice / maxPrice / productIds 四字段
- [x] 2.3 为 `LastRecommendationsSnapshot` 提供 `static from(List<RecommendedItem>)` 工厂方法（计算极值与 productIds 列表，空列表返回空快照）
- [x] 2.4 为 `LastRecommendationsSnapshot.from` 写单元测试覆盖空列表 / 单元素 / 多元素 三种场景

## 3. 推荐层支持新 slot

- [x] 3.1 修改 `SqlFilterBuilder` 增加 priceMin 分支：当 slots.priceMin 是 Number / BigDecimal 时输出 `price >= :priceMin`，并把值放进 named parameters
- [x] 3.2 修改 `SqlFilterBuilder` 增加 excludeProductIds 分支：当 slots.excludeProductIds 是非空 List 时输出 `id NOT IN (:excludeProductIds)`，并做 List<Number> → List<Long> 的安全转换
- [x] 3.3 修改 `RecommendCandidateRetriever.buildFilter` 把 priceMin 与 excludeProductIds 透传给 `SqlFilterBuilder`
- [x] 3.4 写 `SqlFilterBuilder` 单测：单独 priceMin / 单独 excludeProductIds / 两者共存 / 与既有 budget 共存 / 空 List 等价无过滤
- [~] 3.5 写 `RecommendCandidateRetriever` 集成测试（**SKIPPED**：infra 模块未搭建集成测试基础设施；priceMin/excludeProductIds 已被 SqlFilterBuilderTest 单测覆盖；端到端正确性由 task 8.1 人工验证保证）

## 4. OrchestratorService 主体

- [x] 4.1 在 `voice-shopping-business/src/main/java/com/voiceshopping/business/orchestrator/` 新建包并新增 `OrchestratorService.java`
- [x] 4.2 注入依赖：SessionRepository、SessionStateService、ShortTermMemory、VoiceEventPublisher、IntentService、ClarifyService、ClarifyRuleService、ParallelRecommendService、PerspectiveHubService、EmotionService、MeterRegistry、ObjectMapper
- [x] 4.3 注入 `@Value("${voice-shopping.perspective.enabled:false}") boolean perspectiveEnabled`
- [x] 4.4 实现 `EmotionResult handle(String sessionId, Long userId, String utterance)` 方法骨架：UUID 解析、Timer 开启、try-finally Timer 停止
- [x] 4.5 在 handle 入口加 session find（不创建），缺失抛 `NotFoundException("会话不存在: " + sessionId)`
- [x] 4.6 在意图理解之前 `voiceEventPublisher.publish(new UserSpokenEvent(sessionId, userId, utterance, System.currentTimeMillis()))`
- [x] 4.7 在意图理解之前写 USER turn 到 ShortTermMemory（role=USER, content=utterance, agent=null）
- [x] 4.8 加载 `SessionStateService.load(sessionId)`，缺失则用 `new SessionState()` 初始化（带 merchantId 来自 session.merchantId）
- [x] 4.9 调用 `intentService.classify(sessionId, utterance)` 拿到 IntentResult
- [x] 4.10 实现私有方法 `reviseIntent(IntentResult, SessionState)`：先跑规则①（priceDirection 锚定），再跑规则②（信息已足，仅 CLARIFY 触发，复用 ClarifyRuleService.missingSlots），返回最终 IntentEnum 与 mergedSlots
- [x] 4.11 实现私有方法 `mergeSlots(stateSlots, currentSlots)`：currentSlots 中非空字段覆盖 stateSlots
- [x] 4.12 用 switch 表达式按 6 种意图分发：each 分支返回 EmotionResult + 标记本轮 phase 与是否更新 lastRecommendations

## 5. 各意图分支实现

- [x] 5.1 `runRecommendation(...)`：调 ClarifyService.decide，ASK 即返回；READY 调 ParallelRecommendService.recommend；perspective 开关开则调 PerspectiveHubService 把文本拼接到 utterance；调 EmotionService.wrap；返回结果 + RecommendResult.items 用于 lastRecommendations
- [x] 5.2 `runClarify(...)`：调 ClarifyService.decide，ASK 直接构造 EmotionResult(question, [])，pendingAsk = question；READY 委托 `runRecommendation`
- [x] 5.3 `runCompare(...)`：从 SessionState.lastRecommendations 反序列化为 LastRecommendationsSnapshot；缺失 / 失败则委托 `runRecommendation`；按 priceDirection 写 budget / priceMin / excludeProductIds；调 ParallelRecommendService.recommend；调 EmotionService.wrap
- [x] 5.4 `runOrderConfirm(...)`：返回 `new EmotionResult("好，给你下单（完整下单逻辑后续补）", List.of())`
- [x] 5.5 `runChitchat(...)`：调 EmotionService.wrap(sessionId, utterance, RecommendResult.EMPTY)；若 speechText 命中已知推荐式兜底文案则替换为 "不太懂这个，我们聊点你想买啥呗？"
- [x] 5.6 `runOutOfScope(...)`：返回 `new EmotionResult("只负责帮你挑商品，这个问题回头可以找客服处理哈。我们继续聊想买什么？", List.of())`

## 6. 收尾、状态写回与监控

- [x] 6.1 实现包级私有 `ensureCompliant(sessionId, userId, emotionResult)`：log.info 后直接 return emotionResult
- [x] 6.2 调用 ensureCompliant 后写 ASSISTANT turn 到 ShortTermMemory（role=ASSISTANT, content=emotionResult.speechText, agent="OrchestratorService"）
- [x] 6.3 实现私有方法 `persistState(...)`：根据分支结果写 phase / currentIntent / slots / pendingAsk / turnCount + 1 / lastRecommendations，调用 `SessionStateService.save`
- [x] 6.4 lastRecommendations 写入逻辑：仅 RECOMMEND/COMPARE 拿到非空 items 时用 LastRecommendationsSnapshot.from 序列化为 Map（ObjectMapper.convertValue）后赋值
- [x] 6.5 Timer 实例缓存：构造函数里按 6 个 IntentEnum 预建 Timer.builder("voice.shopping.orchestrator.handle").tag("intent", name).register(meterRegistry)
- [x] 6.6 finally 块用最终矫正后的 intent（异常时为 OUT_OF_SCOPE）取出 timer 并 sample.stop(timer)

## 7. 配置与测试

- [x] 7.1 在 `voice-shopping-web/src/main/resources/application.yml` 默认配置中加 `voice-shopping.perspective.enabled: false`
- [x] 7.2 写 `OrchestratorServiceTest`（@SpringBootTest 或 Mockito 单测）覆盖：session 不存在 → NotFoundException
- [x] 7.3 测试用例：意图矫正规则① 触发 / 不触发（priceDirection 各种值 + 上轮 last_recommendations 是否存在）
- [x] 7.4 测试用例：意图矫正规则② 触发 / 不触发（CLARIFY + 槽位齐全）
- [x] 7.5 测试用例：6 个意图分支各跑一次，断言 EmotionResult 内容与 SessionState 写回字段
- [x] 7.6 测试用例：CLARIFY-ASK 不调用 ParallelRecommendService，CLARIFY-READY 调用
- [x] 7.7 测试用例：PRODUCT_COMPARE cheaper 写 budget=maxPrice*0.8；expensive 写 priceMin=minPrice*1.2 且 budget 不变；excludeProductIds 包含上轮所有 id
- [x] 7.8 测试用例：PRODUCT_COMPARE 但 lastRecommendations 缺失 → 退化到 PRODUCT_RECOMMENDATION
- [x] 7.9 测试用例：perspective.enabled = true 时 EmotionService 收到的 utterance 已拼接点评文本；= false 时 PerspectiveHubService 未被调用
- [x] 7.10 测试用例：handle 内每次都发布 UserSpokenEvent；短期记忆按 USER → ASSISTANT 顺序写入；turnCount + 1
- [x] 7.11 测试用例：CHITCHAT 闲聊兜底替换文案命中
- [x] 7.12 跑全量 `mvn -q test` 通过

## 8. 验收

- [x] 8.1 启动应用，手工跑一遍：发起 PRODUCT_RECOMMENDATION → 再发"便宜点"验证 PRODUCT_COMPARE 切换 → 验证 last_recommendations 在 PG 与 Redis 都已更新
- [x] 8.2 打开 perspective.enabled = true 重跑一次推荐链路，确认 EmotionResult.speechText 体现出多视角内容（视情况）
- [x] 8.3 验证 Prometheus `/actuator/prometheus` 端点包含 `voice_shopping_orchestrator_handle_seconds_count{intent="..."}` 指标
- [x] 8.4 `openspec validate add-orchestrator-service --strict` 通过
