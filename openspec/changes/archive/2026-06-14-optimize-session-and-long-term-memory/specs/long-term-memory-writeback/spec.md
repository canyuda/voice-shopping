## ADDED Requirements

### Requirement: LongTermMemoryWriter 组件

系统 SHALL 在 `com.voiceshopping.business.memory` 包下提供 `LongTermMemoryWriter` 组件，封装会话结束时把会话内提到的偏好回流到 `user_profile_dynamic` 表的逻辑。该组件 MUST 通过 Spring `@Async` 异步执行，不阻塞调用方。

#### Scenario: 异步执行不阻塞调用方
- **WHEN** 调用方调用 `flushOnSessionEnd(sessionId, userId)`
- **THEN** 方法立即返回，写入逻辑在 `@Async` 线程池中后台执行

### Requirement: flushOnSessionEnd 方法签名

系统 SHALL 提供方法签名 `void flushOnSessionEnd(String sessionId, Long userId)`。两个参数 MUST 都不为空（null/blank）。当任一参数为空时 MUST 抛 `IllegalArgumentException`，由 fail-fast 原则保证调用方传值正确。

#### Scenario: sessionId 为 null 抛异常
- **WHEN** `flushOnSessionEnd(null, 100L)` 被调用
- **THEN** 抛出 `IllegalArgumentException`

#### Scenario: userId 为 null 抛异常
- **WHEN** `flushOnSessionEnd("sess-1", null)` 被调用
- **THEN** 抛出 `IllegalArgumentException`

### Requirement: 会话提及偏好抽取来源

系统 SHALL 从 `SessionState.slots` 字段中读取会话累积的 `category` 与 `brand` 取值集合作为本次回流的输入。`SessionState.slots` 在每轮 Orchestrator 写回时已合并当前轮 slots，因此 MUST 是会话内语义抽取结果的最终态。

实现层面 MUST 同时支持以下两种 slot 形态：
- 单值字符串：`{"category": "跑鞋"}` → 视为只有一个品类
- 数组形态：`{"category": ["跑鞋", "运动袜"]}` → 视为两个品类

null / 空字符串 / 空数组 MUST 被忽略。

#### Scenario: 单值 category 被回流
- **WHEN** `session_state.slots = {"category": "跑鞋"}` 且会话结束触发 flushOnSessionEnd
- **THEN** "跑鞋" 被加入回流输入集合

#### Scenario: 数组 category 被回流
- **WHEN** `session_state.slots = {"category": ["跑鞋", "运动袜"]}` 且会话结束触发 flushOnSessionEnd
- **THEN** "跑鞋" 与 "运动袜" 都被加入回流输入集合

#### Scenario: 空 slots 不写入也不抛异常
- **WHEN** `session_state.slots = {}` 或 `session_state` 不存在
- **THEN** 方法静默返回，user_profile_dynamic 不被修改，日志 INFO 级别记录"无可回流偏好"

### Requirement: 品类偏好按权重累加

对于每个抽取到的品类（去重后），系统 SHALL 在 `user_profile_dynamic.category_prefs` JSONB 字段中将该品类对应的 Double 值增加 `voice-shopping.memory.long-term.category-mention-weight`（默认 0.05）。原值不存在时按 0 起算。

#### Scenario: 已存在品类累加
- **WHEN** 回流 "跑鞋"，原 `category_prefs = {"跑鞋": 0.10}`
- **THEN** 写入后 `category_prefs.跑鞋 = 0.15`

#### Scenario: 新品类初始化为权重值
- **WHEN** 回流 "跑鞋"，原 `category_prefs = {}`
- **THEN** 写入后 `category_prefs.跑鞋 = 0.05`

#### Scenario: 一次会话同品类只 +1 次
- **WHEN** 同一次 flushOnSessionEnd 中 "跑鞋" 出现于 slots 多次（去重前）
- **THEN** category_prefs 中 "跑鞋" 仅增加一次权重值

### Requirement: 品牌偏好按权重累加

对于每个抽取到的品牌（去重后），系统 SHALL 在 `user_profile_dynamic.brand_prefs` JSONB 字段中将该品牌对应的 Double 值增加 `voice-shopping.memory.long-term.brand-mention-weight`（默认 0.03）。该权重 MUST 显著低于 `voice-shopping.behavior.purchase-weight`（0.15），以反映"提及"信号强度低于"购买"行为。

#### Scenario: 品牌权重低于购买权重
- **WHEN** 默认配置生效
- **THEN** `brand-mention-weight = 0.03`，`purchase-weight = 0.15`，前者严格小于后者

#### Scenario: 新品牌初始化
- **WHEN** 回流 "Nike"，原 `brand_prefs = {}`
- **THEN** 写入后 `brand_prefs.Nike = 0.03`

### Requirement: 缺少 dynamic 记录时初始化

当 `user_profile_dynamic` 中不存在该 userId 对应的记录时，系统 SHALL 在写入前初始化一条新记录，初始字段值参照 `UserBehaviorSink.getOrCreateDynamic` 模式：merchantId=0、categoryPrefs/brandPrefs/recentBehavior 为空集合、purchaseCount=0、createdAt/updatedAt 为当前时间。

#### Scenario: 首次回流自动建档
- **WHEN** userId=200 在 `user_profile_dynamic` 中不存在记录，flushOnSessionEnd 被触发且 slots 含 "跑鞋"
- **THEN** PG 中插入新行，`user_id=200`、`category_prefs={"跑鞋": 0.05}`、`brand_prefs={}`、`purchase_count=0`

### Requirement: price_sensitivity 占位空方法

系统 SHALL 在 `LongTermMemoryWriter` 中提供私有方法 `updatePriceSensitivity(UserProfileDynamic dynamic)`，本版本 MUST 为空实现（仅 TODO 注释），不修改 `dynamic.priceSensitivity` 字段。flushOnSessionEnd 主流程 MUST 调用该方法占位，下个版本实现具体策略。

#### Scenario: 当前版本 priceSensitivity 不被改动
- **WHEN** flushOnSessionEnd 完成，原 `price_sensitivity = "MEDIUM"`
- **THEN** 写入后 `price_sensitivity` 仍为 "MEDIUM"

### Requirement: 写入后 evict 画像缓存

flushOnSessionEnd 在 `dynamicRepo.save(...)` 成功后 MUST 调用 `UserProfileService.evictCache(userId)`，确保下一次画像加载从 PG 读取最新值，不会读到旧的 24h 缓存快照。

#### Scenario: 回流后画像缓存被驱逐
- **WHEN** flushOnSessionEnd 成功写入 PG
- **THEN** `userProfile` 缓存中对应 userId 的条目被驱逐，下次 `UserProfileService.load(userId)` 重新查询 PG

### Requirement: 失败不阻塞调用方

flushOnSessionEnd 内部 MUST 用 try/catch 包住所有异常，异常 MUST 被记录为 ERROR 级别日志（含 sessionId、userId 与异常栈），不能向 `@Async` 线程池外抛出。

#### Scenario: PG 写入失败被记录但不传播
- **WHEN** dynamicRepo.save 抛 DataAccessException
- **THEN** ERROR 日志包含 sessionId、userId 与异常栈，调用方（在主线程）不感知任何异常

### Requirement: MemoryDebugController 手动调试接口

系统 SHALL 在 `voice-shopping-web` 模块提供 `MemoryDebugController`，暴露 `POST /api/v1/debug/memory/flush` 接口。请求体为 Java Record `MemoryFlushRequest(String sessionId, Long userId)`，userId 字段允许为 null。响应统一为 `ApiResult<String>`，data 固定为字符串 `"ok"`。

#### Scenario: 请求体含 userId 直接触发回流
- **WHEN** 请求 body = `{"sessionId":"sess-1","userId":100}`
- **THEN** 接口立即返回 `{code:0, msg:"ok", data:"ok"}`，后台异步执行 `flushOnSessionEnd("sess-1", 100L)`

#### Scenario: userId 为空时通过 sessionId 反查
- **WHEN** 请求 body = `{"sessionId":"sess-1","userId":null}`
- **THEN** 接口先调用 `sessionService.findUserId("sess-1")` 取得 userId，再触发回流；若 sessionId 不存在则抛 `NotFoundException`，由全局异常处理器返回 404

#### Scenario: sessionId 为空抛 IllegalArgumentException
- **WHEN** 请求 body = `{"sessionId":"","userId":100}`
- **THEN** 抛出 `IllegalArgumentException`，由全局异常处理器返回 400

### Requirement: SessionService 提供 findUserId 反查方法

系统 SHALL 在 `SessionService` 上新增方法 `Long findUserId(String sessionId)`：通过 `SessionRepository.findById(sessionId)` 查询返回 `userId`；session 不存在时 MUST 抛 `NotFoundException`。

#### Scenario: 存在的 session 返回 userId
- **WHEN** sessionId 对应的 Session 行存在且 userId=100
- **THEN** `findUserId(sessionId)` 返回 `100L`

#### Scenario: 不存在的 session 抛 NotFoundException
- **WHEN** sessionId 不存在
- **THEN** 抛出 `NotFoundException("会话不存在: " + sessionId)`

### Requirement: long-term 权重配置

系统 SHALL 在 application 配置中支持以下两个属性：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `voice-shopping.memory.long-term.category-mention-weight` | 0.05 | 会话提及品类的累加权重 |
| `voice-shopping.memory.long-term.brand-mention-weight` | 0.03 | 会话提及品牌的累加权重 |

#### Scenario: 自定义权重生效
- **WHEN** 启动时配置 `voice-shopping.memory.long-term.category-mention-weight=0.10`
- **THEN** flushOnSessionEnd 中每个品类按 0.10 累加

### Requirement: Redis Keyspace Notification 启动检查（开关控制）

系统 SHALL 在配置中支持 `voice-shopping.memory.keyspace-notification.check-on-startup`（默认 false），仅当开关打开时在 Spring 容器启动后执行一次 Redis `CONFIG GET notify-keyspace-events`：若返回值不同时包含 'E' 与 'x' 字符，MUST 抛 `BeanInitializationException` 阻止应用启动。开关关闭时 MUST 不执行任何检查。

> 与 SessionExpireListener 配套：listener 启用时强烈建议同时把启动检查开关打开，否则 Redis 缺 `notify-keyspace-events=Ex` 配置会导致 listener 静默不工作。

#### Scenario: 开关关闭跳过检查
- **WHEN** 配置 `voice-shopping.memory.keyspace-notification.check-on-startup=false`
- **THEN** 启动期不执行 CONFIG GET，应用正常启动

#### Scenario: 开关开启且配置正确启动成功
- **WHEN** 开关开启且 Redis 已配置 `notify-keyspace-events=Ex`
- **THEN** 启动期检查通过，应用正常启动

#### Scenario: 开关开启且配置缺失启动失败
- **WHEN** 开关开启且 Redis 配置 `notify-keyspace-events=`（空字符串）或不含 'E' 与 'x'
- **THEN** 启动期抛 `BeanInitializationException`，应用启动失败

### Requirement: 多源触发幂等性（推迟到下个版本）

本版本 **不** 在 `LongTermMemoryWriter` 内部实现幂等去重。`doFlush` 不读 ShortTermMemory，也不在 PG 写入成功后清空 ShortTermMemory；同一会话被多个触发点（WebSocket close / Redis TTL 过期 / Order confirm / 手动调试）连续触发时，`user_profile_dynamic` 中相应品类/品牌权重 MUST 视实际触发次数累加（即可能多次 +0.05 / +0.03）。

> **设计取舍**：本版本先把"会话结束触发回流"的链路打通（WS close + Redis TTL 两条路径），让回流逻辑在生产可观测；幂等控制由调用方在后续版本自行决定 —— 例如在 WS close 路径上调用 `flushOnSessionEnd` 之后立即 `shortTermMemory.clear(sessionId)`，把"何时关门"的决定权交还给调用方。本提案保留头部注释掉的 gate 检查代码块作为下一版本的参考实现。

> **临时风险**：测试环境若同时启用 WS close 触发与 SessionExpireListener，且会话顺利结束，`user_profile_dynamic.category_prefs` 可能被加权 2 次（WS close 一次 + 30min 后 TTL 过期再一次）。线上环境如果 WS 频繁断开重连，可能出现远高于预期的累加。下个版本接入幂等机制后此现象消除。

#### Scenario: doFlush 头部不读 ShortTermMemory
- **WHEN** `flushOnSessionEnd(sessionId, userId)` 被调用
- **THEN** 不调用 `shortTermMemory.recent(sessionId, anyInt)`；进入正常 PG 读写路径

#### Scenario: PG 写入成功后不清空 ShortTermMemory
- **WHEN** `dynamicRepo.save(...)` 成功 → `profileService.evictCache(userId)` 完成
- **THEN** 不调用 `shortTermMemory.clear(sessionId)`；ShortTermMemory 仅由 TTL 自然过期回收

#### Scenario: 同一会话连续触发 PG 各加一次
- **WHEN** 同一 sessionId 连续触发两次 `flushOnSessionEnd(...)`，且 SessionState.slots 含 `category="跑鞋"`
- **THEN** PG `user_profile_dynamic.category_prefs.跑鞋` 累加 2 次权重（如 0.05 + 0.05 = 0.10）

### Requirement: SessionExpireListener Redis 过期触发

系统 SHALL 在 `com.voiceshopping.business.session` 包下提供 `SessionExpireListener extends KeyExpirationEventMessageListener`，通过 `RedisMessageListenerContainer` 订阅 `__keyevent@*__:expired` 频道，处理 `vs:session:` 前缀的过期事件。处理流程 MUST 是：

1. 从 message body 取出过期 key，校验 `startsWith("vs:session:")` 否则忽略
2. 截取 sessionId（去前缀），blank 时 WARN 日志后忽略
3. 调用 `sessionService.findUserId(sessionId)` 反查 userId
4. 调用 `longTermMemoryWriter.flushOnSessionEnd(sessionId, userId)`

监听器 MUST 不向 Redis pub/sub 框架抛出任何异常 —— 任何错误（PG 查询失败、`flushOnSessionEnd` fail-fast guard 命中等）都 MUST 被 catch 并以 ERROR/INFO 级别日志记录，否则后续 listener 派发会被阻断。

#### Scenario: vs:session 前缀 key 过期触发回流
- **WHEN** Redis 发布 `__keyevent@*__:expired` 消息，body = `"vs:session:abc-123"`
- **THEN** listener 调用 `sessionService.findUserId("abc-123")` → `longTermMemoryWriter.flushOnSessionEnd("abc-123", userId)`

#### Scenario: 非 vs:session 前缀 key 被忽略
- **WHEN** body = `"vs:short_memory:abc-123"` 或 `"some-other-app:foo"`
- **THEN** listener 不调用 sessionService 和 longTermMemoryWriter

#### Scenario: session 不存在 INFO 日志后跳过
- **WHEN** sessionId 对应的 Session 行已被清理，`sessionService.findUserId` 抛 `NotFoundException`
- **THEN** listener 不抛异常，不调用 `flushOnSessionEnd`，INFO 日志包含 sessionId

#### Scenario: PG 查询其他异常被 catch
- **WHEN** `sessionService.findUserId` 抛 `DataAccessException`
- **THEN** listener 不抛异常，ERROR 日志含 sessionId 与栈

### Requirement: SessionExpireListener 启用开关

系统 SHALL 在配置中支持 `voice-shopping.memory.session-expire-listener.enabled`（默认 false）。仅当开关打开时 Spring 容器才创建 `RedisMessageListenerContainer` + `SessionExpireListener` Bean；关闭时不订阅任何 Redis 频道，零开销。

#### Scenario: 开关关闭不创建 Bean
- **WHEN** 配置 `voice-shopping.memory.session-expire-listener.enabled=false`（或缺省）
- **THEN** Spring ApplicationContext 中不存在 `SessionExpireListener` Bean，也不创建 `RedisMessageListenerContainer`

#### Scenario: 开关打开装配 listener
- **WHEN** 配置 `voice-shopping.memory.session-expire-listener.enabled=true`
- **THEN** Spring 创建 `RedisMessageListenerContainer` 与 `SessionExpireListener` Bean，监听 `__keyevent@*__:expired`

### Requirement: WebSocket 关闭触发回流

`VoiceWebSocketHandler.afterConnectionClosed` MUST 在握手 attributes 同时含 `bizSessionId` 与 `userId` 时调用 `longTermMemoryWriter.flushOnSessionEnd(bizSessionId, userId)`。该调用 MUST 包裹 try/catch，任何异常仅 WARN 日志记录、不向 Spring WebSocket 框架抛出。多源触发（同一会话 WS close + 之后 TTL 过期）的去重由 LongTermMemoryWriter 内置的 ShortTermMemory 门闸保证，本调用方 MUST NOT 自行做幂等判断。

#### Scenario: 正常关闭触发回流
- **WHEN** WebSocket session 关闭，attributes 中 `bizSessionId` 与 `userId` 都非空
- **THEN** `longTermMemoryWriter.flushOnSessionEnd(bizSessionId, userId)` 被调用一次

#### Scenario: 缺失 attributes 跳过
- **WHEN** attributes 中 `bizSessionId` 为 null（握手拦截器异常路径）
- **THEN** 不调用 flushOnSessionEnd，仍释放 ASR 资源

#### Scenario: flush 调度异常被吞
- **WHEN** flushOnSessionEnd 抛 `IllegalArgumentException`（极端情况：sessionId 为 blank）
- **THEN** WARN 日志记录，afterConnectionClosed 正常完成，不影响后续 ASR / AgentFactory 清理动作
