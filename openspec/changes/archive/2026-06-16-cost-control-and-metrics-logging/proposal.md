## Why

当前一轮推荐对话的 LLM/TTS/ASR 成本中，**Perspective × 3 (qwen-plus) 占 ~46%**，**Emotion (qwen-max) 占 ~38%**。Perspective 团队属于"看起来很厉害但用户感知弱"的能力，默认开启就把单轮成本翻了一倍；CHITCHAT 兜底分支白调一次 EmotionAgent (qwen-max) 再被替换成模板文案，浪费整次调用；EmotionAgent prompt 强制 80-150 字输出导致 TTS 过长，用户不耐烦。同时项目缺乏成本可观测能力——LLM/ASR/TTS 的实际消耗散在主日志里，无法精确度量本次优化的真实收益。本期一次性砍掉这些可砍的浪费，并铺设 logfmt 格式的成本埋点，为下期外部工具扫日志生成成本看板做准备。

## What Changes

- **Perspective 默认关闭**：`voice-shopping.perspective.enabled` 默认值从 `true` 改为 `false`，配置开关保留以便 A/B 测试
- **CHITCHAT 分支零 LLM**：`OrchestratorService.runChitchat` 跳过 `EmotionService.wrap` 调用，从新增的 `ChitchatReplyPool`（10 句兜底文案池）随机抽一句
- **Clarify 单字段模板化**：`missingSlots.size() == 1` 时查 `application.yml` 里 `voice-shopping.clarify.single-slot-templates` 表，多字段仍走 LLM
- **EmotionAgent prompt 缩短**：`emotion-merged.txt` 输出长度约束从"80-150 字"改为"50-100 字"，每款商品理由 ≤10 字，砍掉冗余修饰
- **新增 CostMetricsLogger**：static util 放 `voice-shopping-business/business/cost`，单行 logfmt 格式输出到独立 `cost-metrics.log` 文件
- **8 个调用点埋点**：IntentService / ClarifyService / EmotionService / EmotionStreamingService / PerspectiveHubService / EmbeddingService / ASRService / TTSService 全部接入 CostMetricsLogger
- **EmotionStreamingService 新增 AGENT_RESULT hook**：从 `Msg.getChatUsage()` 提取流式场景的 inputTokens/outputTokens，不影响字幕流
- **logback-spring.xml 新增 CostMetrics appender**：独立文件 + 按天滚动 + 30 天保留
- **删除已废弃代码**：`RecommendReasonService` / `RecAgentBuilder` / `prompts/rec.txt` / `AgentFactory.recAgent` 字段及相关测试（保留 `prompts/emotion.txt` 不删）
- **保持不变**：EmotionAgent 维持 qwen-max + memory keep 40 + 不缓存（保住核心质量），ASR 静音补帧不动，TTS 短词过滤不做，灰度策略不做

## Capabilities

### New Capabilities
- `cost-metrics-logging`: 统一的成本埋点 utility，logfmt 格式输出 LLM/ASR/TTS/EMBEDDING 各场景的 sessionId/userId/model/tokens/durationMs/cacheHit 等字段到独立日志文件
- `chitchat-reply-pool`: 闲聊兜底文案池（10 条），不调 LLM，随机抽取，避免死板回复

### Modified Capabilities
- `orchestrator-service`: `runChitchat` 不再调用 EmotionAgent，改为从 ChitchatReplyPool 取兜底文案
- `clarify-service`: 新增单字段模板查表逻辑（`missingSlots.size() == 1` 直接返回模板问句不调 LLM）
- `emotion-agent`: `emotion-merged.txt` prompt 输出长度约束从 80-150 字改为 50-100 字，每款商品理由约束更严
- `perspective-team`: 默认关闭（`enabled` 默认值 false）
- `intent-service`: 新增 LLM 调用埋点（含 cacheHit 标记）
- `emotion-service`: 新增 LLM 调用埋点
- `emotion-streaming`: 新增 AGENT_RESULT 事件 hook 提取 ChatUsage 并埋点
- `embedding-service`: 新增 EMBEDDING 调用埋点
- `asr-service`: 新增 ASR 调用埋点（session 结束时记录 audioMs）
- `tts-service`: 新增 TTS 调用埋点（synthesize/streamSynthesize 入口记录 inputChars）
- `rec-reason-generation`: REMOVED — `RecommendReasonService` 已在上版本从主链路摘除，本期清理代码

## Impact

- **代码新增**：`voice-shopping-business/business/cost/CostMetricsLogger.java`、`voice-shopping-business/business/agent/ChitchatReplyPool.java`
- **代码删除**：`voice-shopping-business/business/rec/RecommendReasonService.java`、`voice-shopping-ai/ai/agent/rec/RecAgentBuilder.java`、`voice-shopping-ai/resources/prompts/rec.txt`、`AgentFactory` 中 `recBuilder` / `recAgent` 字段及 `getRecAgent` 方法、`AgentMemoryPolicy.beforeRecommendCall` 方法及关联测试
- **代码修改**：8 个调用点（Intent/Clarify/Emotion/EmotionStream/Perspective/Embedding/ASR/TTS Service）方法体新增埋点；`OrchestratorService.runChitchat` 重写；`ClarifyService.decideInternal` 加单字段模板分支
- **配置变更**：`application.yml` 中 `voice-shopping.perspective.enabled` 默认值改为 false，新增 `voice-shopping.clarify.single-slot-templates` 嵌套结构
- **基础设施**：`logback-spring.xml` 新增 CostMetrics RollingFileAppender（输出 `logs/cost-metrics.log`，按天滚动 30 天保留）
- **性能/成本影响**：推荐分支单轮 LLM 成本预期下降约 60%（perspective off 贡献 46%，prompt 缩短贡献 ~14%）；CHITCHAT 分支 LLM 成本下降 100%；CLARIFY 单字段场景 LLM 成本下降 ~60%；TTS 时长砍 30-40%
- **可观测性**：所有 LLM/ASR/TTS/EMBEDDING 调用产生 logfmt 格式日志到 `cost-metrics.log`，下期可用 awk/grep/Loki 直接解析生成成本看板，无需改代码
- **回滚方案**：修改 `application.yml` 把 `perspective.enabled` 改回 true 即可恢复 perspective；其他改动通过 git revert 回滚；CostMetricsLogger 移除埋点不影响业务逻辑
- **不影响项**：EmotionAgent 模型 qwen-max 不变；EmotionAgent memory keep 40 不变；不引入新缓存；`prompts/emotion.txt` 保留作回滚资产
