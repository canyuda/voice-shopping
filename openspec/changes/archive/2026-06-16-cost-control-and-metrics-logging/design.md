## Context

上版本（latency-optimization-h5-streaming）把推荐分支的 LLM 调用从 ReasonLLM + EmotionLLM 合并为单次 EmotionAgent 流式调用。但单轮总成本仍然偏高，原因有三：

1. **Perspective 团队默认开启** — 每轮推荐都额外调 3 次 qwen-plus（PriceAdvisor / ProRunner / BeginnerBuyer），占单轮 LLM token 约 46%
2. **CHITCHAT 兜底分支白调一次 EmotionAgent** — `runChitchat` 调 `EmotionService.wrap` 拿到推荐式空兜底文案，再判断为推荐式空兜底替换为闲聊兜底；这次 qwen-max 调用 100% 浪费
3. **EmotionAgent prompt 鼓励 80-150 字输出** — 用户耳朵听不下这么长的回复，TTS 时长和模型输出 token 同时虚高

同时项目缺乏成本可观测能力：当前主日志混杂 trace 信息，无法精确分场景统计 LLM/ASR/TTS 消耗，本次改动收益也无法精确度量。

DashScope 的实际计费维度明确：
- LLM：按 input/output tokens 分档计费（qwen-max > qwen-plus > qwen-turbo）
- ASR：按音频时长（秒）计费
- TTS：按合成字符数计费
- Embedding：按 input tokens 计费

AgentScope SDK 直接暴露 `Msg.getChatUsage()` 返回 `ChatUsage{inputTokens, outputTokens, totalTokens, time}` —— **不需要自己估算 token**。

## Goals / Non-Goals

**Goals:**
- 推荐分支单轮 LLM 成本下降 ≥50%（Perspective off 贡献最大头）
- CHITCHAT 分支 LLM 成本下降 100%
- CLARIFY 单字段场景 LLM 成本下降 ~60%
- 所有 LLM/ASR/TTS/EMBEDDING 调用产生统一 logfmt 格式日志，可用通用工具直接解析
- TTS 输出时长缩短 30-40%（prompt 50-100 字 vs 80-150 字）
- 清理上版本遗留的死代码

**Non-Goals:**
- 不动 EmotionAgent 的 qwen-max 模型（保住核心情感应答质量）
- 不缩短 EmotionAgent memory keep 40（保住情感连续性）
- 不引入新的 LLM 缓存（IntentCache 已有，其他场景命中率不确定）
- 不做实时成本告警（本期只产数据，下期外部工具消费）
- 不做灰度发布
- 不做 ASR 静音帧本地过滤（场景小且有破坏 VAD 风险）
- 不做 TTS 短词过滤（当前没有产生短词的代码路径）

## Decisions

### D1: Perspective 默认关闭，开关保留

**选择**：`voice-shopping.perspective.enabled` 默认值从 `true` 改为 `false`。代码不删，AgentFactory 的 `newPerspectiveTeam()` 和 PerspectiveAgentBuilder 都保留。

**备选方案**：
- A. 完全删除 perspective 相关代码 → 放弃，未来可能恢复或做转化率 A/B
- B. 保留默认开启，但模型从 qwen-plus 降到 qwen-turbo → 放弃，用户表态"功能本身价值未验证，不该花钱跑 A/B"

**理由**：开关默认关 = 直接省 46% token；开关保留 = 不损失任何能力，需要做 A/B 时一行配置即可恢复。

### D2: runChitchat 跳过 LLM，从 ChitchatReplyPool 随机选

**选择**：新增 `ChitchatReplyPool`（business 模块，static util 类持有 `List<String> REPLIES`），10 句兜底文案，`runChitchat` 用 `ThreadLocalRandom.current().nextInt(REPLIES.size())` 随机抽一句返回。

**备选方案**：
- A. 单一固定文案（当前替换后行为） → 放弃，每次同一句话太死板
- B. 让 EmotionAgent 用专门的 chitchat prompt 真生成 → 放弃，"花钱让 LLM 输出闲聊客套话"投入产出比极低

**理由**：用户表态"死板的回答会降低粘性"，10 句池保证 90% 概率不重复；池内文案均统一引导回购物场景，避免对话失焦。

10 句兜底文案（已敲定）：
```
"这话题我接不上，要不你说想看点啥？"
"我更擅长帮你挑东西，要不试试？"
"这个不太懂，咱聊点你想买的呗？"
"嗯，你想找啥商品我可以帮你看看"
"这事我搞不定，购物的话尽管问"
"聊不来这个，要不告诉我你想买啥"
"我帮你挑商品比较在行，说说需求？"
"这个跳过吧，你想买点什么？"
"我对这个没研究，购物的话可以聊"
"这话题超纲了，要不看看商品？"
```

### D3: Clarify 单字段查表，多字段调 LLM

**选择**：在 `application.yml` 新增 `voice-shopping.clarify.single-slot-templates` 通用模板表（不按 category 定制）。`ClarifyService.decideInternal` 在 `missingSlots.size() == 1` 时查表直接构造 `ClarifyResult.ask(template, missingSlots)`，跳过 LLM。

**备选方案**：
- A. 全部模板化（所有字段组合都建表） → 放弃，组合爆炸难维护
- B. 按 category 嵌套定制 → 放弃，5 个跑鞋的精准问句维护成本远大于通用模板的"还行"问句

**理由**：单字段问句自然语言只有一种说法（"你预算大概多少？"），LLM 的价值是组合多字段时让句式更自然；按猜测分布，60% 场景缺 1 个字段、30% 缺 2 个、10% 缺 3+，单字段模板能覆盖大部分调用。

模板表（首批 4 个常用 slot）：
```yaml
voice-shopping:
  clarify:
    single-slot-templates:
      scenario: "你一般在什么场地用？"
      budget: "你预算大概多少？"
      gender: "是男款还是女款？"
      brand: "你有想要的品牌吗？"
```

### D4: Emotion prompt 输出 50-100 字

**选择**：修改 `prompts/emotion-merged.txt`，输出长度约束从"整段 80-150 字"改为"整段 50-100 字"，每款商品理由约束从"一条最核心的 reason，不展开参数"细化为"≤10 字，砍掉冗余修饰"。

**备选方案**：
- A. 50-80 字（更激进） → 放弃，砍掉情感开场会失去差异化
- B. 维持 80-150 字 → 放弃，TTS 太长用户不耐烦

**理由**：用户表态"用户更需要的是简短清晰的回答而不是废话"。50-100 字保留情感腔调（开场 + 三款 + 收尾问句结构不变），但每款卖点压缩到 10 字以内（"中底厚实缓震好" → "缓震最好"），TTS 时长直接砍 30-40%。

### D5: CostMetricsLogger 用 logfmt 格式 + static util + 独立日志文件

**选择**：
- 类型：`public final class CostMetricsLogger`，static 方法 `logLlm` / `logAsr` / `logTts` / `logEmbedding`
- 位置：`voice-shopping-business/business/cost/CostMetricsLogger.java`
- 日志格式：单行 logfmt（`key=value` 空格分隔），中文/特殊字符值用引号包裹
- Logger name：`CostMetrics`，logback 配置独立 RollingFileAppender 输出到 `logs/cost-metrics.log`，按天滚动 30 天保留
- 不污染主日志（`additivity="false"`）

**备选方案**：
- A. JSON 格式 → 放弃，logfmt 单行更紧凑、grep/awk 友好、Loki 原生支持
- B. Component bean + 注入 → 放弃，static util 无注入开销，到处可调
- C. 输出到 stdout 主日志 → 放弃，量大会污染主日志

**字段约定**：

| 字段 | LLM | ASR | TTS | EMBEDDING |
|------|-----|-----|-----|-----------|
| scene | ✓ | ✓ | ✓ | ✓ |
| sessionId | ✓ | ✓ | ✓ | ✓ |
| userId | ✓ | ✓ | ✓ | ✓ |
| model | ✓ | ✓ | ✓ | ✓ |
| agent | ✓ | - | - | - |
| inputChars | ✓ | - | ✓ | ✓ |
| outputChars | ✓ | - | - | - |
| inputTokens | ✓ | - | - | ✓ |
| outputTokens | ✓ | - | - | - |
| totalTokens | ✓ | - | - | - |
| audioMs | - | ✓ | - | - |
| durationMs | ✓ | ✓ | ✓ | ✓ |
| cacheHit | ✓ (LLM) | - | - | - |

示例输出：
```
2026-06-16 10:23:45.123 scene=LLM agent=emotion sessionId=test-xxx userId=101 model=qwen-max inputChars=234 outputChars=87 inputTokens=180 outputTokens=58 totalTokens=238 durationMs=4200 cacheHit=false
2026-06-16 10:23:45.150 scene=LLM agent=intent sessionId=test-xxx userId=101 cacheHit=true durationMs=2
2026-06-16 10:23:45.200 scene=ASR sessionId=test-xxx userId=101 audioMs=4500 model=paraformer-realtime-v2 durationMs=4500
2026-06-16 10:23:50.500 scene=TTS sessionId=test-xxx userId=101 inputChars=87 model=cosyvoice-v1 durationMs=1800
2026-06-16 10:23:45.080 scene=EMBEDDING sessionId=test-xxx userId=101 inputChars=15 model=text-embedding-v3 inputTokens=12 durationMs=240
```

### D6: EmotionStreamingService 流式场景的 ChatUsage 提取

**选择**：保留对 `REASONING + isLast=true` 和 `AGENT_RESULT` 事件的过滤（不进文本流，避免重复播报），但在 `AGENT_RESULT` 事件 hook 里提取 `Msg.getChatUsage()` 用于成本埋点。

```java
agent.stream(userMessage)
    .doOnNext(event -> {
        // 命中 AGENT_RESULT 时提取 ChatUsage
        if (event != null && event.getType() == EventType.AGENT_RESULT) {
            ChatUsage usage = event.getMessage().getChatUsage();
            CostMetricsLogger.logLlm(scene, "emotion_stream", sessionId, userId,
                    "qwen-max", inputChars, outputChars,
                    usage.getInputTokens(), usage.getOutputTokens(),
                    usage.getTotalTokens(), durationMs, false);
        }
    })
    .filter(event -> event.getType() == EventType.REASONING && !event.isLast())
    .map(event -> event.getMessage().getTextContent())
    ...
```

**备选方案**：
- A. 不埋点，估算 tokens（inputChars / 1.5） → 放弃，SDK 直接给精确值
- B. 在 doOnComplete 里统计 → 放弃，complete 时拿不到累计的 input tokens

### D7: 删除已废弃代码

**选择**：删除 `RecommendReasonService` / `RecAgentBuilder` / `prompts/rec.txt` / `AgentFactory.recAgent` 字段 / `AgentMemoryPolicy.beforeRecommendCall` 及相关测试。**保留 `prompts/emotion.txt`**（用户表态本期不动）。

**备选方案**：
- A. 全部保留作回滚资产 → 放弃，回滚走 git revert 比留死代码更干净
- B. 全部删除（包括 emotion.txt） → 用户明确要求 emotion.txt 不删

**理由**：上版本已经把 RecommendReasonService 从主链路摘除，至今未触发回滚。死代码增加新人理解成本，且让 codegraph 等工具产生噪音。

## Risks / Trade-offs

- **[Risk] Perspective 默认关闭后转化率下降** → 缓解：开关保留，未来可在生产环境开启做 A/B；如果数据证明 perspective 对转化率有显著正向影响，重新打开仅需改一行配置。当前没有数据证明 perspective 有正收益，先省钱。

- **[Risk] CHITCHAT 池 10 句话久而久之被用户摸清规律** → 缓解：用户极少高频触发 CHITCHAT（语音购物场景下大部分输入都被分类为 PRODUCT_RECOMMENDATION/CLARIFY/COMPARE/ORDER），10 句池足够；后期如发现命中重复率高再扩展到 20-30 句。

- **[Risk] Clarify 单字段模板问句过于死板** → 缓解：4 个核心 slot 的模板已经足够自然（如"你预算大概多少？"），不像 LLM 那种千人千面但成本高；如果未来发现某 slot 模板问句导致用户答非所问，再为该 slot 单独放开走 LLM。

- **[Risk] Emotion prompt 50-100 字版本质量下降，用户感觉信息不全** → 缓解：用户能在右侧产品卡片看到完整属性/价格，TTS 仅作"快速定向"；prompt 仍保留"开场 + 三款 + 收尾问句"结构，只是每款描述更短；上线后可观察用户复购/留存数据，必要时回退到 80-150 字。

- **[Risk] CostMetricsLogger 阻塞主链路** → 缓解：使用 logback 异步 appender（AsyncAppender 包装 RollingFileAppender），主线程仅入队不等待 IO；Logger.info 调用本身在 logback 同步路径也只是字符串拼接，开销可忽略。

- **[Risk] 删除 RecAgentBuilder 后 AgentFactory.AgentSet 结构不兼容** → 缓解：AgentSet 是 record，删除字段是 breaking change，但 AgentSet 仅在 AgentFactory 内部使用，影响面小；同步删除 `getRecAgent` 方法及相关测试即可。

- **[Trade-off] 不引入新缓存** → 上版本讨论过的 EmotionResult/Clarify 缓存命中率不确定，本期不做以避免设计漏洞。CHITCHAT 池本质是预生成缓存的极简形态。

- **[Trade-off] Logfmt vs JSON 格式** → 选 logfmt 是因为单行紧凑（一行 ~200 字节）、grep/awk 直接解析、Loki/Splunk 原生支持。代价是不能直接 JSON.parse，但日志消费方一般是流式工具，logfmt 反而更友好。

## Migration Plan

1. **阶段一：埋点先行（不改业务逻辑）**
   - 新增 `CostMetricsLogger` 类
   - 在 8 个调用点接入埋点（不修改业务行为）
   - 新增 `logback-spring.xml` 配置 CostMetrics appender
   - 验证：跑一轮对话，检查 `cost-metrics.log` 文件生成正常、字段完整、无主日志污染

2. **阶段二：成本砍刀**
   - `application.yml` 改 `perspective.enabled: false`
   - 重写 `runChitchat` + 新增 `ChitchatReplyPool`
   - 新增 `clarify.single-slot-templates` 配置 + 改 `ClarifyService.decideInternal`
   - 改 `emotion-merged.txt` prompt 输出长度
   - 验证：对照阶段一日志，确认推荐分支 LLM token 数下降 ≥50%、CHITCHAT 0 LLM 调用

3. **阶段三：清理死代码**
   - 删除 RecommendReasonService 等 5 个文件 + AgentFactory/AgentMemoryPolicy 中相关字段方法
   - 删除关联测试
   - 验证：`mvn compile` 通过，所有现有测试绿

**回滚方案**：
- 默认开关恢复：改一行 `application.yml` 的 `perspective.enabled: true` 即可
- prompt 回滚：把 `emotion-merged.txt` 改回 80-150 字版本
- 死代码恢复：`git revert` 删除提交
- 埋点不影响业务，可保留
