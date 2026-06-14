## 1. AgentMemoryPolicy + 四个 Service 接入

- [x] 1.1 在 `voice-shopping-business/src/main/java/com/voiceshopping/business/agent/` 下新增 `AgentMemoryPolicy.java`，提供 `beforeIntentCall` / `beforeClarifyCall` / `beforeRecommendCall` / `beforeEmotionCall` 四个公开方法及私有 `trimToLast(agent, limit, tag)`，用 `@Component` 注册为 Spring Bean
- [x] 1.2 实现 `trimToLast`：先做 size 边界判断（`agent.getMemory().getMessages().size() > limit` 才进循环），循环调用 `agent.getMemory().deleteMessage(0)`，DEBUG 日志带 tag
- [x] 1.3 改造 `IntentService.classify`：构造方法注入 `AgentMemoryPolicy`，删除原来的 `agent.getMemory().clear()` 直接调用，改为 `policy.beforeIntentCall(agent)`，必须在 `agent.call(...)` 之前
- [x] 1.4 改造 `ClarifyService.decide`：构造方法注入 `AgentMemoryPolicy`，删除被注释的 `// agent.getMemory().clear();` 那行（不保留注释），新增 `policy.beforeClarifyCall(agent)`
- [x] 1.5 改造 `RecommendReasonService.attachReasons`：构造方法注入 `AgentMemoryPolicy`，在 `agent.call(...)` 之前调用 `policy.beforeRecommendCall(agent)`
- [x] 1.6 改造 `EmotionService.wrap`：构造方法注入 `AgentMemoryPolicy`，在 `agent.call(...)` 之前调用 `policy.beforeEmotionCall(agent)`
- [x] 1.7 新增 `AgentMemoryPolicyTest`：覆盖 `trimToLast` 边界（limit=0 / 内存为空 / 内存正好等于 limit / 超过 limit）+ 四个 before* 方法的预期行为
- [x] 1.8 全文检索确认：`voice-shopping-business/src/main/java` 下除 `AgentMemoryPolicy.java` 外不再出现 `agent.getMemory().clear()` 或 `agent.getMemory().deleteMessage(`

## 2. TurnSummarizer + ShortTermMemory.Turn 文档更新

- [x] 2.1 在 `voice-shopping-business/src/main/java/com/voiceshopping/business/memory/` 下新增 `TurnSummarizer.java`，`@Component`，单方法 `String summarize(String userUtterance, IntentEnum intent, String agentReply)`，模板严格为 `"[%s] 用户：%s / 助手：%s"`
- [x] 2.2 TurnSummarizer 入参非空校验：userUtterance 与 intent 为 null 时抛 `IllegalArgumentException`（fail-fast）；agentReply 允许空字符串
- [x] 2.3 更新 `ShortTermMemory.Turn` 的 Javadoc：role 合法取值集合追加 `TURN`，并说明 TURN 角色的 `agent` 字段持有该轮最终矫正后 IntentEnum.name()（同 ASSISTANT）
- [x] 2.4 新增 `TurnSummarizerTest`：标准格式 + 空 agentReply + null 入参抛异常三个场景

## 3. OrchestratorService.handle append 顺序调整

- [x] 3.1 `OrchestratorService` 构造方法注入 `TurnSummarizer`，字段以 final 定义
- [x] 3.2 删除 `OrchestratorService.handle` 第 154 行的 `shortTermMemory.append("USER", utterance, ...)` 整段（含上方注释）
- [x] 3.3 保留第 180 行 ASSISTANT append 不动，紧接其后追加：调用 `turnSummarizer.summarize(utterance, finalIntent, safeReply.speechText())` 拿到 summary，再 `shortTermMemory.append(sessionId, new Turn("TURN", summary, finalIntent.name(), Instant.now()))`
- [x] 3.4 更新 `OrchestratorServiceTest` 中关于"短期记忆中本轮新增的 turn 顺序"的断言：从"USER + ASSISTANT"改为"ASSISTANT + TURN"，新增 TURN 内容包含 `[FINAL_INTENT]` 前缀的断言
- [x] 3.5 新增端到端断言：异常路径（dispatch 抛异常）下短期记忆不新增 ASSISTANT 或 TURN

## 4. LongTermMemoryWriter

- [x] 4.1 在 `voice-shopping-business/src/main/java/com/voiceshopping/business/memory/` 下新增 `LongTermMemoryWriter.java`，`@Component`，构造方法注入 `SessionStateService` / `UserProfileDynamicRepository` / `UserProfileService`，配置项 `voice-shopping.memory.long-term.category-mention-weight`（默认 0.05）和 `voice-shopping.memory.long-term.brand-mention-weight`（默认 0.03）通过 `@Value` 注入
- [x] 4.2 主方法 `@Async public void flushOnSessionEnd(String sessionId, Long userId)`：fail-fast 校验两个参数非空；内部全程 try/catch 兜异常，异常 ERROR 日志含 sessionId + userId + 栈
- [x] 4.3 实现私有方法 `Set<String> extractStrings(Object slotValue)`：null/空字符串/空集合 → 空 Set；String → 单元素；List/Collection → 过滤空字符串后转 Set；其他类型 → 空 Set + WARN 日志
- [x] 4.4 实现品类/品牌抽取：`sessionStateService.load(sessionId)` 拿 SessionState，对 `slots.get("category")` 和 `slots.get("brand")` 分别走 `extractStrings`；二者都为空时 INFO 日志"无可回流偏好"并 return
- [x] 4.5 实现 `getOrCreateDynamic(userId)`：参考 `UserBehaviorSink.getOrCreateDynamic` 模式 —— `dynamicRepo.findByUserId(userId)` 没命中则 new 一条 merchantId=0、空集合的初始记录
- [x] 4.6 累加逻辑：对去重后的 categories/brands 集合分别遍历，调 `dynamic.getCategoryPrefs().merge(name, weight, Double::sum)` 累加
- [x] 4.7 实现私有方法 `updatePriceSensitivity(UserProfileDynamic dynamic)`：空方法体 + Javadoc TODO，主流程 MUST 调用占位
- [x] 4.8 收尾：`dynamicRepo.save(dynamic)` 后调用 `userProfileService.evictCache(userId)`
- [x] 4.9 `SessionService` 新增 `Long findUserId(String sessionId)`：`sessionRepository.findById(sessionId)` 不存在抛 `NotFoundException("会话不存在: " + sessionId)`，存在则返回 `getUserId()`
- [x] 4.10 新增 `LongTermMemoryWriterTest`：覆盖 single category 单值 + List<String> + 空 slots + 已存在 dynamic 累加 + 不存在 dynamic 自动建档 + price_sensitivity 不变 + evictCache 被调用 + PG 写入失败被捕获不传播

## 5. MemoryDebugController

- [x] 5.1 在 `voice-shopping-web/src/main/java/com/voiceshopping/web/controller/` 下新增 `MemoryDebugController.java`，`@RestController` `@RequestMapping("/api/v1/debug/memory")`
- [x] 5.2 在合适位置（`voice-shopping-common` 或 controller 同包）新增 Java Record `MemoryFlushRequest(String sessionId, Long userId)`
- [x] 5.3 接口实现：`@PostMapping("/flush") public ApiResult<String> flush(@RequestBody MemoryFlushRequest req)` —— fail-fast 校验 `sessionId` 非空白；userId 为空时 `sessionService.findUserId(req.sessionId())` 反查；调用 `longTermMemoryWriter.flushOnSessionEnd(...)` 后 return `ApiResult.ok("ok")`
- [x] 5.4 鉴权策略对齐：检查 `ProfileDebugController` / `ChatDebugController` 是否带 `@SaCheckLogin` 等注解，跟随保持一致（既有 debug 接口均未启用 Sa-Token，本接口同样不加）
- [x] 5.5 新增 `MemoryDebugControllerTest`：覆盖 userId 显式 / userId 反查 / sessionId 空白抛 400 / sessionId 不存在抛 404

## 6. SessionStateService 双写鲁棒性收尾

- [x] 6.1 `SessionStateService.save` 方法添加 `@Transactional(noRollbackFor = Exception.class)` 注解（import `org.springframework.transaction.annotation.Transactional`）
- [x] 6.2 `writeToRedis` 失败的 ERROR 日志文案统一为：`"Redis 同步失败，下次 load 会从 PG 重建 sessionId={}"` 含具体 sessionId
- [x] 6.3 `load` 方法 Redis miss 回退 PG 的 `log.warn` 文案统一调整，明确"下次 load 会从 PG 重建"语义
- [x] 6.4 新增/补充 `SessionStateServiceTest`：mock Redis 写入抛 RuntimeException 验证 PG 写入未回滚 + 日志含统一文案

## 7. Redis Keyspace Notification 启动检查（开关默认关闭）

- [x] 7.1 在 `voice-shopping-infrastructure` 的 `RedisConfig`（或同模块新建 `KeyspaceNotificationStartupChecker.java`）中实现一个 `@PostConstruct` 或 `ApplicationRunner` Bean
- [x] 7.2 通过 `@Value("${voice-shopping.memory.keyspace-notification.check-on-startup:false}")` 注入开关；为 false 时直接 return（实现走 `@ConditionalOnProperty`，效果等价：开关关闭 Bean 不创建）
- [x] 7.3 开关为 true 时执行 `redisTemplate.execute((RedisCallback<List<String>>) connection -> connection.serverCommands().getConfig("notify-keyspace-events"))`，校验返回值同时含 'E' 与 'x'；不满足则抛 `BeanInitializationException("Redis notify-keyspace-events 未启用 'Ex'，请配置后再启动")`
- [x] 7.4 新增对应配置项默认值到 `voice-shopping-web/src/main/resources/application.yml`（值为 false，并写注释说明用途）

## 8. 验证 & 文档

- [x] 8.1 全模块 `mvn -DskipTests=false test` 通过（business 96 + web 5 = 101 个单测全部通过）
- [x] 8.2 启动应用，按 `docs/data/agent-dto-specifications.md` / debug 接口模板补一段 `MemoryDebugController` 调用示例和回流前后 `user_profile_dynamic` 对比的截图/日志（人工执行步骤，由用户在本会话外执行；产物在用户本地，未落到本仓库 docs/）
- [x] 8.3 在 `CLAUDE.md` 的"已实现功能"清单追加：四个 Agent 内部记忆策略集中管理 / 短期记忆 TURN 摘要 / 长期记忆会话结束回流（手动调试）/ Redis keyspace 启动检查（开关）
- [x] 8.4 运行 `openspec validate optimize-session-and-long-term-memory --strict`，无 error 后 commit

## 9. 幂等门闸 + 触发点接入（追加范围）

> 本组任务原本计划放到下一版本，但因为 ShortTermMemory 自然提供了幂等门闸（PG 写成功后 clear，下次空跑），实际实现成本远低于 SETNX 方案，所以提前做掉。proposal.md / specs 已同步刷新。

- [x] 9.1 `LongTermMemoryWriter` 构造函数注入 `ShortTermMemory`，`SHORT_TERM_GATE_LIMIT=50` 常量（保留供下版本启用）
- [~] 9.2 `doFlush` 入口先 `shortTermMemory.recent(sessionId, 50)`，空则直接 return（不查 PG、不写、不 evict、不 clear）—— 实现后用户改为**注释保留代码**，本版本 gate 不启用，多源触发会重复加权
- [~] 9.3 `doFlush` 在 `dynamicRepo.save → profileService.evictCache` 成功之后调用 `shortTermMemory.clear(sessionId)`；前置失败路径（无 state、空 slots、无 mentions、save 抛异常）MUST 不调 clear —— 实现后用户改为**删除 clear 调用**，本版本由 STM TTL 自然过期，下版本由调用方自行决定 clear 时机
- [x] 9.4 `LongTermMemoryWriterTest` 同步清理：删除 `flush_emptyShortTermMemory_isNoOp` / `flush_successPath_clearsShortTermMemory` 两个用例（gate 注释 / clear 删除导致测试断言不再成立）；移除 3 个保留用例中的 `verify(shortTermMemory, never()).clear(any())` 无效断言
- [x] 9.5 `SessionStateService.save` 改为带 TTL 的三参 `valueOps.set(key, json, ttl)`；构造函数注入 `voice-shopping.memory.session-state.ttl`（默认 30m）
- [x] 9.6 同步更新 `SessionStateServiceTest`：mock 改为三参 stub，验证 TTL 等于 `Duration.ofMinutes(30)`
- [x] 9.7 新增 `SessionExpireListener extends KeyExpirationEventMessageListener`：监听 `__keyevent@*__:expired`，过滤 `vs:session:` 前缀，反查 userId 后 flushOnSessionEnd；任何异常 catch 不外抛
- [x] 9.8 新增 `SessionExpireListenerConfig`：`@ConditionalOnProperty(voice-shopping.memory.session-expire-listener.enabled=true)`，装配 `RedisMessageListenerContainer` + listener Bean
- [x] 9.9 改造 `VoiceWebSocketHandler`：注入 `LongTermMemoryWriter`，`afterConnectionClosed` 释放 ASR 后调用 `flushOnSessionEnd(bizSessionId, userId)`，try/catch 保护
- [x] 9.10 application.yml 新增 `voice-shopping.memory.session-state.ttl=30m` 与 `voice-shopping.memory.session-expire-listener.enabled=false` 默认值与注释
- [x] 9.11 `docs/data/NOTES.md` 追加风险点：clear ShortTermMemory 的可观测性与会话历史丢失影响（注：clear 调用最终被删，文档仍记录原方案下的取舍，待本版本风险讨论收敛后会更新）

## 10. 短期记忆归档方案文档

- [x] 10.1 评估 `session_message` 表当前使用情况（结论：零代码引用，仅 V1 schema + 文档/注释）
- [x] 10.2 写归档方案到 `docs/short-term-memory-archive.md`：复用 session_message 而非新建 log 表；V4 迁移放宽 role CHECK 含 TURN；archive 在 evictCache 之后、clear 之前 best-effort；6 项决策摘要与 7 步实施清单

> 落实方案（开新 OpenSpec change `add-short-term-memory-archive` 走 propose → apply）推迟到下个版本，不计入本 change 任务清单。
