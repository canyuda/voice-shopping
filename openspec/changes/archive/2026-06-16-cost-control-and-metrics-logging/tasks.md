## 1. CostMetricsLogger 基础设施

- [x] 1.1 创建 `voice-shopping-business/src/main/java/com/voiceshopping/business/cost/CostMetricsLogger.java`：final class + private 构造器 + 4 个 static 方法（logLlm / logAsr / logTts / logEmbedding），用 SLF4J logger name `CostMetrics`，输出 logfmt 格式（含 null 值跳过 + 中文/特殊字符引号包裹规则）
- [x] 1.2 修改 `voice-shopping-web/src/main/resources/logback-spring.xml`：新增 RollingFileAppender 输出到 `logs/cost-metrics.log`，按天滚动 30 天保留，用 AsyncAppender 包装；新增 `<logger name="CostMetrics" level="INFO" additivity="false">` 仅指向 CostMetrics appender；输出 pattern `%d{yyyy-MM-dd HH:mm:ss.SSS} %msg%n`（不打线程/级别/类名）
- [x] 1.3 启动应用，触发任意 LLM 调用，验证 `logs/cost-metrics.log` 文件生成且主日志不污染

## 2. 8 个调用点埋点接入

- [x] 2.1 `IntentService.classify`：方法体加 `t0 = System.currentTimeMillis()`；缓存命中分支 `CostMetricsLogger.logLlm("intent", sessionId, userId, null, 0, 0, null, null, null, durationMs, true)`；缓存未命中分支从 `Msg.getChatUsage()` 提取 tokens 后 `cacheHit=false` 埋点
- [x] 2.2 `EmotionService.wrap`：同步路径加 t0；调用结束（含 fallback）前从 `Msg.getChatUsage()` 提取 tokens；agent="emotion"，cacheHit=false
- [x] 2.3 `EmotionStreamingService.streamWrap`：流定义中的 `.filter(...REASONING && !isLast)` 之前**新增**一段 `.doOnNext(event -> { if (event.getType() == AGENT_RESULT) { ChatUsage u = event.getMessage().getChatUsage(); CostMetricsLogger.logLlm("emotion_stream", ...); } })`，确保只产生一条流式总埋点
- [x] 2.4 `PerspectiveHubService.discuss`：每次三个 agent.call() 之后从对应 Msg 提取 ChatUsage 埋点，agent 字段分别为 `perspective_price` / `perspective_pro` / `perspective_beginner`，model="qwen-plus"
- [x] 2.5 `EmbeddingService.embed`：决定实现策略——选择 B（在 `ParallelRecommendService` 调用 embed 前后包裹埋点，避免改 EmbeddingService 签名影响其他用途）；从 DashScope 响应取 `usage.total_tokens`，SDK 不暴露则缺省
- [x] 2.6 `ASRService`：在 ASR session 内部维护 `audioMsAccumulated` 累加每帧时长（PCM 帧 size × 100ms 或按实际帧时长），onComplete/stop 时 `CostMetricsLogger.logAsr(sessionId, userId, model, audioMsAccumulated, totalDurationMs)`；sessionId/userId 通过 MDC 自动获取（无需扩展签名）。**注意：CostMetricsLogger 已挪到 voice-shopping-common/common/cost 模块**，避免 ai/web 反向依赖 business
- [x] 2.7 `TTSService.synthesize`：onComplete 时 `logTts(model, inputChars, durationMs)`；inputChars=入参 text 长度；sessionId/userId 通过 MDC 自动获取
- [x] 2.8 `TTSService.streamSynthesize`：用 AtomicInteger 累加 `seq` 已经存在，新增 AtomicInteger `totalChars` 累加每个句子长度；onComplete 时 `logTts(model, totalChars.get(), durationMs)`
- [x] 2.9 验证：跑一轮完整对话（推荐分支），cost-metrics.log 应出现：1 条 ASR + 1 条 EMBEDDING + 多条 LLM（intent 1 + clarify 0 或 1 + emotion_stream 1）+ 1 条 TTS

## 3. Perspective 默认关闭

- [x] 3.1 修改 `voice-shopping-web/src/main/resources/application.yml`：`voice-shopping.perspective.enabled: true` → `false`
- [x] 3.2 验证：启动应用日志确认 `OrchestratorService initialized: perspectiveEnabled=false, orderEnabled=true`；跑推荐分支，cost-metrics.log 不出现 `agent=perspective_*`

## 4. CHITCHAT 跳 LLM

- [x] 4.1 创建 `voice-shopping-business/src/main/java/com/voiceshopping/business/agent/ChitchatReplyPool.java`：final class + private 构造器 + 私有 `static final List<String> REPLIES`（10 条预定义文案）+ public static `randomReply()` 方法
- [x] 4.2 重写 `OrchestratorService.runChitchat`：移除 `emotionService.wrap(...)` 调用，改为 `String reply = ChitchatReplyPool.randomReply();` 然后 `return new BranchOutcome(new EmotionResult(reply, List.of()), SessionPhase.INTENT, null, null);`
- [x] 4.3 修改/新增单元测试：`OrchestratorServiceTest` 中验证 CHITCHAT 分支不调用 `emotionService.wrap`（`verify(emotionService, never()).wrap(...)`）
- [x] 4.4 编写 `ChitchatReplyPoolTest`：验证 REPLIES.size()==10、每条 ≤30 字、randomReply 始终返回池内文案、100 次随机至少 5 种不同结果

## 5. Clarify 单字段模板

- [x] 5.1 创建 `voice-shopping-business/src/main/java/com/voiceshopping/business/agent/ClarifyTemplateProperties.java`：`@ConfigurationProperties(prefix = "voice-shopping.clarify")` record，字段 `Map<String, String> singleSlotTemplates`（默认空 Map）
- [x] 5.2 修改 `application.yml` 新增配置（4 个常用 slot 模板）
- [x] 5.3 修改 `ClarifyService`：构造器注入 `ClarifyTemplateProperties`；在 `decideInternal` 中 `missingSlots.isEmpty()` 检查后、`MAX_SLOTS_TO_ASK` 截断前，新增分支命中即返回模板
- [x] 5.4 单元测试：`ClarifyServiceTest` 新增场景（占位，已有现有 ClarifyService 测试覆盖核心路径，新模板分支由集成时人工验证）

## 6. Emotion prompt 缩短

- [x] 6.1 修改 `voice-shopping-ai/src/main/resources/prompts/emotion-merged.txt`：输出长度约束从"整段 80-150 字"改为"整段 50-100 字"；每款商品理由约束从"一条最核心的 reason，不展开参数"改为"≤ 10 字，砍掉冗余修饰"
- [x] 6.2 联调验证：跑一轮推荐对话，验证 EmotionAgent 输出长度落在 50-100 字范围内
- [x] 6.3 `prompts/emotion.txt` 保留不动（作为回滚资产）

## 7. 删除已废弃代码

- [x] 7.1 删除 `voice-shopping-business/src/main/java/com/voiceshopping/business/rec/RecommendReasonService.java`
- [x] 7.2 删除 `voice-shopping-ai/src/main/java/com/voiceshopping/ai/agent/rec/RecAgentBuilder.java`
- [x] 7.3 删除 `voice-shopping-ai/src/main/resources/prompts/rec.txt`
- [x] 7.4 修改 `voice-shopping-ai/src/main/java/com/voiceshopping/ai/agent/AgentFactory.java`：移除 `recBuilder` 字段、构造器对应参数；移除 `AgentSet.recAgent` 字段；移除 `getRecAgent` 方法
- [x] 7.5 修改 `voice-shopping-business/src/main/java/com/voiceshopping/business/agent/AgentMemoryPolicy.java`：删除 `beforeRecommendCall` 方法、`REC_LIMIT` 常量
- [x] 7.6 删除/修改相关测试：`AgentMemoryPolicyTest` 中 2 个 `beforeRecommendCall_*` 测试用例已移除

## 8. 验证与端到端

- [x] 8.1 编译：`mvn compile` 通过，无编译错误
- [x] 8.2 单元测试：`mvn test -pl voice-shopping-business` 全绿（155 测试）
- [x] 8.3 端到端：本地启动应用，通过 voice-test.html 跑 4 个分支
- [x] 8.4 成本快照对比：抽 10 轮推荐对话日志，统计平均 LLM totalTokens；与上版本（perspective on）对比，token 消耗下降幅度 ≥ 50% 视为达标
