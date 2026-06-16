## Purpose

成本指标日志能力，统一记录 LLM / ASR / TTS / EMBEDDING 调用的输入规模、token、耗时与缓存命中情况，并输出到独立日志文件用于成本分析。

## Requirements

### Requirement: CostMetricsLogger 静态工具类

系统 SHALL 在 `com.voiceshopping.business.cost` 包下提供 `CostMetricsLogger` final class，仅包含 static 方法，不允许实例化（私有构造器）。

提供四个 static 入口方法（按场景）：

```java
public static void logLlm(String agent, String sessionId, Long userId,
                          String model, int inputChars, int outputChars,
                          Integer inputTokens, Integer outputTokens, Integer totalTokens,
                          long durationMs, boolean cacheHit);
public static void logAsr(String sessionId, Long userId, String model,
                          long audioMs, long durationMs);
public static void logTts(String sessionId, Long userId, String model,
                          int inputChars, long durationMs);
public static void logEmbedding(String sessionId, Long userId, String model,
                                int inputChars, Integer inputTokens, long durationMs);
```

#### Scenario: 类不可实例化
- **WHEN** 试图通过反射调用 CostMetricsLogger 私有构造器
- **THEN** 抛 UnsupportedOperationException 或类似异常，确保只能 static 调用

#### Scenario: logLlm 输出标准 logfmt 行
- **WHEN** 调用 `CostMetricsLogger.logLlm("emotion", "test-xxx", 101L, "qwen-max", 234, 87, 180, 58, 238, 4200L, false)`
- **THEN** 在 `CostMetrics` logger 输出一条 INFO 日志，单行 logfmt 格式：
  `scene=LLM agent=emotion sessionId=test-xxx userId=101 model=qwen-max inputChars=234 outputChars=87 inputTokens=180 outputTokens=58 totalTokens=238 durationMs=4200 cacheHit=false`

#### Scenario: cacheHit=true 时不写 token 字段
- **WHEN** 调用 `CostMetricsLogger.logLlm("intent", "test-xxx", 101L, null, 0, 0, null, null, null, 2L, true)`
- **THEN** 输出格式：`scene=LLM agent=intent sessionId=test-xxx userId=101 cacheHit=true durationMs=2`，省略 model/inputChars/outputChars/tokens 字段

#### Scenario: logAsr 输出格式
- **WHEN** 调用 `CostMetricsLogger.logAsr("test-xxx", 101L, "paraformer-realtime-v2", 4500L, 4500L)`
- **THEN** 输出：`scene=ASR sessionId=test-xxx userId=101 model=paraformer-realtime-v2 audioMs=4500 durationMs=4500`

#### Scenario: logTts 输出格式
- **WHEN** 调用 `CostMetricsLogger.logTts("test-xxx", 101L, "cosyvoice-v1", 87, 1800L)`
- **THEN** 输出：`scene=TTS sessionId=test-xxx userId=101 model=cosyvoice-v1 inputChars=87 durationMs=1800`

#### Scenario: logEmbedding 输出格式
- **WHEN** 调用 `CostMetricsLogger.logEmbedding("test-xxx", 101L, "text-embedding-v3", 15, 12, 240L)`
- **THEN** 输出：`scene=EMBEDDING sessionId=test-xxx userId=101 model=text-embedding-v3 inputChars=15 inputTokens=12 durationMs=240`

#### Scenario: null 值跳过
- **WHEN** sessionId 或 userId 为 null
- **THEN** 该字段不出现在输出中（不输出 `sessionId=null`），但仍输出其他必填字段

### Requirement: 独立的 CostMetrics Logger 与 Appender 配置

系统 SHALL 在 `voice-shopping-web/src/main/resources/logback-spring.xml` 配置：
- 名为 `CostMetrics` 的 logger，level=INFO，additivity=false（不向上传播到主日志）
- 独立的 `RollingFileAppender`，输出到 `logs/cost-metrics.log`
- 滚动策略：按天滚动（`%d{yyyy-MM-dd}.log.gz`），保留最近 30 天
- 用 `AsyncAppender` 包装，避免 IO 阻塞业务主线程
- 输出 pattern：`%d{yyyy-MM-dd HH:mm:ss.SSS} %msg%n`（不打线程/级别/包名，纯净 logfmt）

#### Scenario: 主日志不含 CostMetrics 内容
- **WHEN** 业务调用 `CostMetricsLogger.logLlm(...)`
- **THEN** 主应用日志（如 `application.log` / 控制台）不出现该条日志，仅 `cost-metrics.log` 出现

#### Scenario: 文件按天滚动
- **WHEN** 跨天产生日志
- **THEN** 上一天日志压缩归档为 `cost-metrics.YYYY-MM-DD.log.gz`，当天日志写入 `cost-metrics.log`

### Requirement: 八大调用点必须埋点

下列调用点 MUST 在调用 LLM/ASR/TTS/EMBEDDING 后通过 CostMetricsLogger 埋点：

| 调用点 | scene | agent | 关键说明 |
|--------|-------|-------|---------|
| IntentService.classify | LLM | intent | cache hit 时 cacheHit=true 不调 LLM |
| ClarifyService.decide | LLM | clarify | 单字段模板分支不埋点（未调 LLM） |
| EmotionService.wrap | LLM | emotion | 同步调用 |
| EmotionStreamingService.streamWrap | LLM | emotion_stream | 通过 AGENT_RESULT 事件 hook 提取 ChatUsage |
| PerspectiveHubService.discuss | LLM | perspective | 三个 agent 各埋一次（agent=perspective_price/pro/beginner） |
| EmbeddingService.embed | EMBEDDING | - | DashScope 返回的 input_tokens 写入 |
| ASRService session 结束 | ASR | - | audioMs 来自累计音频帧时长 |
| TTSService.synthesize / streamSynthesize | TTS | - | inputChars 来自全部喂入 DashScope 的字符数总和 |

#### Scenario: IntentService 缓存命中埋点
- **WHEN** IntentService.classify 命中 IntentCache
- **THEN** 输出 `scene=LLM agent=intent ... cacheHit=true durationMs=<2`，不输出 inputTokens/outputTokens 字段

#### Scenario: IntentService 缓存未命中埋点
- **WHEN** IntentService.classify 未命中 IntentCache，调用 qwen-turbo
- **THEN** 输出 `scene=LLM agent=intent model=qwen-turbo inputTokens=<n> outputTokens=<m> ... cacheHit=false`

#### Scenario: ClarifyService 单字段模板分支不埋点
- **WHEN** ClarifyService.decide 走单字段模板路径（未调 LLM）
- **THEN** 不产生 scene=LLM 的成本日志

#### Scenario: ClarifyService 多字段调 LLM 埋点
- **WHEN** ClarifyService.decide 走 LLM 路径
- **THEN** 输出 `scene=LLM agent=clarify model=qwen-turbo inputTokens=<n> outputTokens=<m> ...`

#### Scenario: EmotionStreamingService 通过 AGENT_RESULT hook 埋点
- **WHEN** EmotionStreamingService.streamWrap 的 Flux 收到 EventType.AGENT_RESULT
- **THEN** 从 `event.getMessage().getChatUsage()` 提取 inputTokens/outputTokens/totalTokens 并埋点
- **THEN** AGENT_RESULT 事件本身仍被过滤掉，不进入文本流（保持上版本"避免重复播报"行为）

#### Scenario: PerspectiveHubService 三个 agent 各自埋点
- **WHEN** PerspectiveHubService.discuss 完整执行三角色点评
- **THEN** 在 cost-metrics.log 中出现 3 条 `scene=LLM agent=perspective_price/perspective_pro/perspective_beginner` 日志，model=qwen-plus

### Requirement: durationMs 字段语义

`durationMs` 字段 MUST 反映**该次调用从入口方法开始到出口的实际耗时**，包括缓存查询/反序列化/网络 IO 等所有时间。

#### Scenario: durationMs 包含 LLM 调用时间
- **WHEN** EmotionService.wrap 入口处 `t0=System.currentTimeMillis()`，出口前 `durationMs = System.currentTimeMillis() - t0`
- **THEN** durationMs ≈ LLM API 网络往返时间 + JSON 解析时间

#### Scenario: 流式场景 durationMs
- **WHEN** EmotionStreamingService.streamWrap 收到 AGENT_RESULT
- **THEN** durationMs = AGENT_RESULT 时刻 - 流订阅开始时刻

### Requirement: logfmt 编码规则

字段值 SHALL 按以下规则编码：

- 纯字母/数字/常用符号（`_`/`-`/`.`/`:`/`/`/`+`）：直接输出，不加引号
- 含空格、引号、`=`、中文：用双引号包裹，内部双引号用 `\"` 转义
- null：跳过该字段
- Boolean：输出 `true` / `false`（小写）
- 数字：直接输出，不加引号

#### Scenario: 含中文模型名加引号
- **WHEN** model 字段值为 `qwen-max-latest`
- **THEN** 输出 `model=qwen-max-latest`（无引号，因为只含字母/数字/`-`）

#### Scenario: 字段值含空格加引号
- **WHEN** 某字段值为 `paraformer realtime`（含空格，假设场景）
- **THEN** 输出 `field="paraformer realtime"`
