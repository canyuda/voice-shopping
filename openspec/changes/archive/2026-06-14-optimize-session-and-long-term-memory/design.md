## Context

项目当前的三层记忆架构（PG 长期 / Redis 会话短期 / Agent InMemoryMemory）已经搭起来，但缺少几块"收口"的工程实践：

- `AgentFactory` 按 sessionId LRU 缓存 ReActAgent 实例（`AgentFactory.java:50`），AgentScope 框架自动把每次 `agent.call` 的 user/assistant 写入 InMemoryMemory，但项目里只有 IntentService 一处显式 `agent.getMemory().clear()`，ClarifyService 那行 `// agent.getMemory().clear();` 还被注释掉，Rec/Emotion 完全没管。结果是长会话中 token 单调上涨。
- `OrchestratorService.handle` 每轮先 append USER 再 append ASSISTANT，IntentService 的 `recent(3)` 拼出来的"最近 3 条"实际上常常被同一轮的 USER 原话和 ASSISTANT 回复占满，对意图判断的额外信息量很有限。
- `UserBehaviorSink` 处理浏览/购买事件，但用户在对话里口头说过的品类品牌没有任何机制写回 `user_profile_dynamic`。会话结束就丢了。

本次提案要把这三件事一次性收口，但**会话结束触发逻辑**（WS close / Order confirm / TTL listener / 幂等去重）暂不实现 —— 触发点合并的去重策略需要更多设计，先把回流核心和调试入口跑通。

涉及代码模块：`voice-shopping-business`（agent / memory / orchestrator / session）、`voice-shopping-web`（Controller + RedisConfig 一项）、不涉及 `voice-shopping-infrastructure` 表结构变更。

## Goals / Non-Goals

**Goals:**
- 四个 Worker Agent 的 InMemoryMemory 清理时机集中由 `AgentMemoryPolicy` 管理，业务代码不再直接操作 `agent.getMemory()`。
- 短期记忆每轮只写一条紧凑的 TURN 摘要 + 一条 ASSISTANT 原文（用于其他观测场景），删除 USER 重复条目，让 IntentAgent `recent(3)` 视窗高效利用。
- 会话级品类/品牌偏好在会话结束时按可配置权重回流到 `user_profile_dynamic`，权重显著低于真实购买行为，避免污染画像。
- 提供 `POST /api/v1/debug/memory/flush` 调试入口，作为本版本回流逻辑的唯一触发点和验证手段。
- `SessionStateService.save` 显式标注 `@Transactional(noRollbackFor)`，PG/Redis 双写鲁棒性写到 spec 一致。
- 为下个版本的 SessionExpireListener 预留 Redis Keyspace Notification 启动检查（开关默认关闭）。

**Non-Goals:**
- 不实现 WebSocket close / Order confirm / Redis TTL 三个会话结束触发点。
- 不实现三触发点的幂等去重机制。
- 不实现 SessionExpireListener。
- 不实现 price_sensitivity 的具体更新策略（留空方法占位，下个版本再做）。
- 不修改 `user_profile_dynamic` / `session_state` / `session` 表结构。
- 不引入新的 Spring Boot 依赖；所有变更基于已有 starters。

## Decisions

### Decision 1：ClarifyAgent 也每轮 clear（纳入统一 policy）

**选项:**
- A. 每轮 clear，纳入 AgentMemoryPolicy.beforeClarifyCall ✅
- B. trim 到 N 条
- C. 完全不管

**选 A。** ClarifyService 每轮拿当前 slots 重新算，历史轮没有信息价值。把 ClarifyAgent 也接入 AgentMemoryPolicy 让"四个 Agent 走统一收口"，避免出现"特例 Agent"。`ClarifyService.java:60` 那行被注释的 `// agent.getMemory().clear();` 是历史遗留犹豫，本次直接落地为 `policy.beforeClarifyCall(agent)`，并删除注释。

### Decision 2：保留 RecAgent trim 到 8 条而非每轮 clear

**选项:**
- A. trimToLast(8) ✅（原方案）
- B. clear()

**选 A。** Rec Agent 当前确实每轮拿 `userNeeds + products` 重做 reasons 生成，看起来 clear 也行；但保留 8 条能给 LLM 留一些"用户上一轮拒绝/接受了什么"的隐式连贯信号。代价只是多 ~2K token，在可接受范围。等下个版本评估实际效果再考虑是否调成 clear。

### Decision 3：EmotionAgent 双重历史并存

**选项:**
- A. 同时读 ShortTermMemory（摘要）和保留 InMemoryMemory（原始对话）✅
- B. 只读 ShortTermMemory，每轮 clear InMemoryMemory

**选 A。** ShortTermMemory 摘要紧凑、用于跨轮的判断（如"用户是否犹豫/反复"）；InMemoryMemory 保留原始 user/assistant，更适合 Emotion 做细粒度情绪追踪（语气、用词变化）。两者覆盖窗口重叠但粒度不同，并不构成"重复信号"。InMemoryMemory 通过 `trimToLast(40)` 控制 token 上限。

### Decision 4：长期回流权重 category 0.05、brand 0.03（低于购买 0.15）

**选项:**
- A. category 0.05 / brand 0.03 ✅
- B. category 0.05 / brand 0.15（原 spec，与 purchaseWeight 等价）
- C. 全部统一 0.05

**选 A。** 用户原 spec 把 brand 提及权重设为 0.15，与 `UserBehaviorSink.purchaseWeight=0.15` 等价 —— 但"会话中口头提到品牌"和"实际购买这个品牌的商品"信号强度不可同日而语。把 brand 提及降到 0.03 保持"提及 < 浏览 < 购买"的强度梯度。两个权重都做成可配置项 (`voice-shopping.memory.long-term.category-mention-weight` / `brand-mention-weight`)，方便后续调校。

### Decision 5：会话提及偏好的数据来源 = SessionState.slots

**选项:**
- A. 从 `SessionState.slots` 读 ✅
- B. 遍历 ShortTermMemory 的 TURN 摘要做正则/NER 抽取
- C. 直接遍历 PG `session_message`

**选 A。** SessionState.slots 是 IntentAgent 已经做完语义抽取的产物，在 Orchestrator 的 `mergeSlots(stateSlots, currentSlots)` 中按"当前覆盖历史"的语义累积 —— 这正是我们要回流的最终态。不需要再做正则 / NER 这种廉价但易错的二次抽取，也不需要查 PG 的 session_message 重做。

但 slots 里的字段类型不统一：有时 `category` 是 String，有时是 List/数组（IntentAgent prompt 没硬约束）。LongTermMemoryWriter MUST 同时容忍两种形态：单值字符串视为单元素集合，数组按多元素处理，null/空字符串/空数组忽略。

### Decision 6：本版本不实现触发点和幂等

**选项:**
- A. 本版本只实现 LongTermMemoryWriter + 调试接口 ✅
- B. 一并实现 WS close 触发
- C. 一并实现三触发点 + Redis SETNX 幂等

**选 A。** 三个触发点合并的去重策略需要回答几个问题（同一会话两秒内 WS close 和 ORDER_CONFIRM 都触发怎么办？应用重启时 TTL 过期消息丢失要不要补偿？SETNX TTL 设多少？），现在还没有线上数据支撑判断。先把"被触发后做什么"做对，"什么时候被触发"留到下个版本，靠手动调试接口先验证回流逻辑正确性。

### Decision 7：Keyspace Notification 启动检查开关默认关闭

**选项:**
- A. 默认 false，业务侧需要 listener 时显式打开 ✅
- B. 默认 true，启动期就强制检查
- C. 不加开关，哪个版本要 listener 哪个版本加检查

**选 A。** 本版本不实现 listener，强制检查会拦住默认配置的 Redis 启动；但加进来也不浪费，给下个版本留好基础设施。开关默认关闭对当前版本零影响，下个版本只需要把 application.yml 改成 true 就能开启 fail-fast。

### Decision 8：MemoryDebugController 同步返回 / 后台异步执行

`flushOnSessionEnd` 标注 `@Async` —— 调试接口直接 invoke 后立即返回 `ApiResult.ok("ok")`，写入 PG 在后台线程池跑。**不**做"同步等回流完成"模式，理由：调试用例下回流可能涉及 PG 写入 + 缓存驱逐，时间不确定；接口侧只需要确认"已派发"。如果要看实际写入结果，调用方自行查 `GET /api/v1/debug/profile/{userId}` 验证。

异常处理：`@Async` 方法的异常不会传播到调用线程，LongTermMemoryWriter 内部 MUST try/catch 全部异常并 ERROR 日志记录，而不是依赖 Spring 默认的 AsyncUncaughtExceptionHandler。

## Risks / Trade-offs

- **[Risk] OrchestratorServiceTest 既有断言会失败**
  → 当前 `OrchestratorServiceTest` 大概率断言"短期记忆中本轮新增两条 turn 按时间顺序为先 USER 后 ASSISTANT"。本次改为 ASSISTANT + TURN，相关断言需同步更新。Mitigation：tasks.md 显式列出测试更新任务，搭配 spec MODIFIED 中的新场景做参考。

- **[Risk] AgentScope deleteMessage(0) 在内存为空时的语义未明确**
  → AgentScope 1.0.11 `InMemoryMemory.deleteMessage(int index)` 文档没说边界行为。trimToLast 的 while 循环 MUST 先检查 `getMemory().getMessages().size() > limit` 再删除，保证空内存或刚好等于 limit 时不进入循环、不调用 deleteMessage。Mitigation：单测覆盖 limit=0、内存为空、内存正好等于 limit 三种边界。

- **[Risk] slots 的 category/brand 字段值结构未来漂移**
  → 现在 IntentAgent 的 prompt 没强制约束 `category` 是 String 还是 List，未来 prompt 调整可能引入更多形态（嵌套对象、Set 等）。Mitigation：LongTermMemoryWriter 抽取代码集中在一个私有方法 `extractStrings(Object slotValue)`，遇到未知形态返回空集合并 WARN 日志，不抛异常；后续若 slot 协议升级只改这一处。

- **[Risk] @Async 静默失败**
  → 调试接口立即返回 "ok"，但回流可能在后台写 PG 失败，调用方不知情。Mitigation：所有异常 ERROR 级别日志含 sessionId、userId、异常栈；运维侧按 ERROR 关键字 `"flushOnSessionEnd failed"` 配告警。

- **[Trade-off] 不实现触发点等于"功能不可用"**
  → 本版本上线后真实会话结束时品类/品牌不会自动回流。Mitigation：明确写在 proposal "本版本范围"，且调试接口存在让 QA 可以手动验证逻辑；下一版本紧跟实现触发点。

- **[Trade-off] price_sensitivity 留空方法**
  → 调用链路完整但效果为零。Mitigation：方法名 `updatePriceSensitivity` 显式 + Javadoc TODO 标注，下个版本自带"该方法已存在"的引导线索，避免新人重复写一份。

## Migration Plan

无数据迁移。代码改动顺序：

1. 先合入 AgentMemoryPolicy + 四个 Service 接入（这一波纯重构，不改外部行为）。
2. 合入 TurnSummarizer + ShortTermMemory.Turn 文档更新 + OrchestratorService.handle append 顺序调整（这一步需要同步修改 OrchestratorServiceTest）。
3. 合入 LongTermMemoryWriter + MemoryDebugController + SessionService.findUserId。
4. 合入 SessionStateService 双写日志和 `@Transactional` 微调（独立小动作）。
5. 合入 RedisConfig 启动检查开关（开关默认 false，对部署无破坏性）。

回滚策略：5 个改动都是新增 Bean / 单点修改，可以独立 revert。最关键回滚点是步骤 2，回滚需要把 OrchestratorService.handle 的两个 append 顺序还原。

## Open Questions

- **Q1：AgentScope `InMemoryMemory.deleteMessage(int)` 是否抛 IndexOutOfBoundsException？**
  实现阶段需要在 `trimToLast` 循环里先做 size 判断，避免依赖未文档化的边界行为。
- **Q2：会话期间用户多次提到不同的品类（如先聊跑鞋后改聊运动袜），SessionState.slots 最终是哪个？**
  当前 `mergeSlots` 用"当前覆盖"语义，所以 slots.category 只会保留**最后**一次提到的值。如果想覆盖整个会话所有提及，需要改成"累积进 set"。本提案先按 slots 当前态回流，等观察数据再判断是否要扩展为全量累积（届时是 spec 升级，不阻塞本版本）。
- **Q3：MemoryDebugController 是否需要鉴权？**
  其他 debug 接口（ProfileDebugController / ChatDebugController）是否走 Sa-Token 拦截？本次 MemoryDebugController MUST 与既有 debug 接口保持一致策略 —— 实现阶段先看 `ProfileDebugController` 是否带 `@SaCheckLogin` 等注解，跟随。
