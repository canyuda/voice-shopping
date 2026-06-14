## Why

项目记忆分三层（PG 长期 / Redis 会话短期 / Agent 内部 InMemoryMemory），当前实现存在三个明显问题：

1. **Agent 内部记忆爆炸**：AgentFactory 按 sessionId 缓存 ReActAgent 实例，AgentScope 框架在每次 `agent.call` 时自动写入 user/assistant 消息，但没有任何收口策略 —— 长会话下 token 线性增长，且历史轮次会干扰当前判断（尤其 IntentAgent 的状态化错误）。
2. **会话短期记忆冗余**：`OrchestratorService.handle` 每轮写两条（USER + ASSISTANT），下一轮 IntentAgent 读 `recent(3)` 时配额一半被同一轮的 USER 原话吃掉，且 USER + ASSISTANT 拆分对意图判断没有额外价值。
3. **跨会话长期记忆缺失会话结束回流**：`UserBehaviorSink` 只覆盖浏览/购买行为，但用户在对话中"提到"过的品类品牌没有任何机制回流到 `user_profile_dynamic`，下一次会话画像无法复用。

## What Changes

- **新增 AgentMemoryPolicy**：集中收口四个 Agent 的 InMemoryMemory 清理时机，提供 `beforeIntentCall` / `beforeClarifyCall` / `beforeRecommendCall` / `beforeEmotionCall` 四个接入点。
- **新增 TurnSummarizer**：每轮把 `(intent, userUtterance, agentReply)` 合并成一条紧凑摘要，作为新的短期记忆载体。
- **改造 ShortTermMemory.Turn**：role 新增 `TURN` 取值；OrchestratorService.handle 的 USER append 删除，保留 ASSISTANT append，再追加一条 TURN 摘要。
- **新增 LongTermMemoryWriter**：实现 `flushOnSessionEnd(sessionId, userId)` 方法，会话提到过的品类 +0.05、品牌 +0.03 写入 `user_profile_dynamic`；price_sensitivity 留空方法 TODO。**本版本不在 writer 内部做幂等去重**：doFlush 头部保留注释掉的 ShortTermMemory gate 检查代码（供下个版本参考），当前不读 STM 也不在 PG 写入成功后清空 STM。多源触发的去重时机交由调用方在下一版本自行决定。
- **新增 MemoryDebugController**：`POST /api/v1/debug/memory/flush` 手动触发回流，作为本版本人工验证入口。
- **新增 SessionExpireListener**：`extends KeyExpirationEventMessageListener`，监听 `__keyevent@*__:expired` 频道、过滤 `vs:session:` 前缀，反查 userId 后调 `flushOnSessionEnd`。由开关 `voice-shopping.memory.session-expire-listener.enabled`（默认 false）控制装配，需配合 Redis `notify-keyspace-events=Ex`。
- **改造 VoiceWebSocketHandler.afterConnectionClosed**：在释放 ASR 资源 / Agent 缓存的同时调用 `flushOnSessionEnd(bizSessionId, userId)`；本版本调用方亦不做 STM clear，与 writer 内部一致接受多源触发可能重复加权的风险。
- **改造 IntentService / ClarifyService / RecommendReasonService / EmotionService**：调用 AgentMemoryPolicy 替代各自的零散清理（IntentService 当前的 `agent.getMemory().clear()` 收回，ClarifyService 注释掉的 clear 行收回）。
- **改造 SessionStateService**：`save` 方法补充 `@Transactional(noRollbackFor = RedisConnectionFailureException.class)`，Redis 写入改为带 TTL 的三参 `set(key, json, ttl)`，TTL 来自配置 `voice-shopping.memory.session-state.ttl`（默认 30m）—— 这是 SessionExpireListener 真正能被触发的前提。Redis 失败日志统一为"Redis 同步失败，下次 load 会从 PG 重建"语义。
- **新增配置开关 `voice-shopping.memory.keyspace-notification.check-on-startup`**（默认 false）：打开时启动期检查 Redis `notify-keyspace-events` 是否包含 `Ex`，未配置则 fail-fast。配套 SessionExpireListener 使用，避免 listener 静默失效。

**仍不在本版本范围**（下一版本再做）：
- Order confirm 触发 flushOnSessionEnd（本版本只接 WS close + Redis TTL 两个点）
- 多源触发幂等去重（gate + clear 机制 / 调用方 clear / SETNX 等替代方案）
- price_sensitivity 实际更新策略

## Capabilities

### New Capabilities
- `agent-memory-policy`：集中管理四个 Worker Agent 的 InMemoryMemory 清理策略（clear / trim-to-N），收口所有 `agent.getMemory()` 直接操作。
- `long-term-memory-writeback`：会话结束回流逻辑 —— 把会话中提到的品类/品牌按权重累加到 `user_profile_dynamic`，包含手动调试入口。

### Modified Capabilities
- `short-term-memory`：Turn 模型新增 `TURN` 角色；写入策略由"每轮 USER+ASSISTANT 两条"改为"每轮 ASSISTANT + TURN 两条"，由 Orchestrator 调用 TurnSummarizer 生成 TURN。
- `orchestrator-service`：handle 方法不再前置 append USER；ASSISTANT append 后追加一条 TURN 摘要。
- `session-management`：`SessionStateService.save` 显式 `@Transactional(noRollbackFor = RedisConnectionFailureException.class)`，Redis 写入带可配置 TTL（默认 30m），日志语义统一。
- `voice-websocket`：`afterConnectionClosed` 触发 `LongTermMemoryWriter.flushOnSessionEnd` + `AgentFactory.remove`，长期记忆在会话结束时回流到 `user_profile_dynamic`。

## Impact

- **代码影响**：
  - `voice-shopping-business/src/main/java/com/voiceshopping/business/agent/`：新增 AgentMemoryPolicy；改造 IntentService、ClarifyService、EmotionService。
  - `voice-shopping-business/src/main/java/com/voiceshopping/business/memory/`：新增 TurnSummarizer、LongTermMemoryWriter；ShortTermMemory.Turn 文档说明新增 TURN 角色。
  - `voice-shopping-business/src/main/java/com/voiceshopping/business/orchestrator/OrchestratorService.java`：handle 方法 append 逻辑调整。
  - `voice-shopping-business/src/main/java/com/voiceshopping/business/rec/RecommendReasonService.java`：接入 AgentMemoryPolicy.beforeRecommendCall。
  - `voice-shopping-business/src/main/java/com/voiceshopping/business/session/SessionStateService.java`：补 `@Transactional`、调整日志。
  - `voice-shopping-web/src/main/java/com/voiceshopping/web/controller/`：新增 MemoryDebugController。
  - `voice-shopping-infrastructure` 的 `RedisConfig`：新增可选 keyspace-notification 启动检查。
- **数据影响**：
  - 不新增表/字段；通过既有 `user_profile_dynamic.category_prefs` / `brand_prefs` JSONB 字段累加。
- **配置影响**：
  - 新增 `voice-shopping.memory.keyspace-notification.check-on-startup`（默认 false）。
  - 新增 `voice-shopping.memory.long-term.category-mention-weight`（默认 0.05）。
  - 新增 `voice-shopping.memory.long-term.brand-mention-weight`（默认 0.03）。
- **依赖与调用方**：
  - OrchestratorService 在不破坏现有契约的前提下注入 TurnSummarizer。
  - IntentService.classify 行为对外不变（仍按 recent(3) 拼接历史），但 recent 返回的内容由"USER+ASSISTANT 交替"变为"TURN 摘要序列"，已对齐其内部 prompt 拼接格式。
- **测试影响**：
  - `OrchestratorServiceTest` 既有断言可能涉及 append 顺序/角色，需要更新。
  - 新增 AgentMemoryPolicyTest、TurnSummarizerTest、LongTermMemoryWriterTest。
