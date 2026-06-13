# voice-event-bus

## Purpose

语音应用内部事件总线能力：基于 Spring `ApplicationEventPublisher` + `@Async @EventListener` 实现 `UserSpokenEvent` 等领域事件的发布/订阅，用于缓存预热、审计日志等异步副作用，避免阻塞主请求链。

## Requirements

### Requirement: UserSpokenEvent 事件载荷
系统 SHALL 在 `com.voiceshopping.common.event` 包下提供 record `UserSpokenEvent(String sessionId, Long userId, String utterance, long timestamp)`。

约束：
- `sessionId` MUST 非空。
- `userId` MAY 为 null（匿名场景）。
- `timestamp` MUST 是事件构造时的 epoch millis（由调用方传入或在 publisher 内填充）。
- record 不得包含业务逻辑方法。

#### Scenario: 创建事件载荷
- **WHEN** `new UserSpokenEvent("s1", 1L, "买跑鞋", System.currentTimeMillis())`
- **THEN** 构造成功，四个字段可被无副作用读取

### Requirement: VoiceEventPublisher 事件发布
系统 SHALL 在 `com.voiceshopping.business.event` 包下提供 `@Component VoiceEventPublisher`，封装 Spring `ApplicationEventPublisher`。

约束：
- 提供 `void publish(UserSpokenEvent event)` 方法，内部委托 `publisher.publishEvent(event)`。
- MUST NOT 在 publisher 内捕获监听器异常（监听器异常归属 listener 边界，由 `@Async` 异常处理器接管）。
- MUST NOT 直接调用监听器方法。

#### Scenario: 发布后所有监听器被触发
- **WHEN** 调用 `publisher.publish(event)` 且容器内同时存在多个 `@EventListener` 监听该事件
- **THEN** 所有监听器都被触发（同步或异步取决于监听器自身注解）

### Requirement: VoiceEventListeners 异步监听器
系统 SHALL 在 `com.voiceshopping.business.event` 包下提供 `@Component VoiceEventListeners`，包含两个用 `@Async @EventListener` 标注的方法：

- `void onUserSpokenWarmup(UserSpokenEvent event)`：用于缓存预热（profile / 候选 / 意图缓存等），失败 MUST 仅日志记录、MUST NOT 抛业务异常。
- `void onUserSpokenAudit(UserSpokenEvent event)`：用于审计日志，MUST 输出 INFO 级别日志包含 `sessionId / userId / utterance` 三字段。

约束：
- 两个方法 MUST 同时携带 `@Async` 与 `@EventListener` 注解。
- 项目 `@EnableAsync` MUST 已启用（已在 `VoiceShoppingApplication` 上）。
- 监听器内异常 MUST 不传递回 `publisher.publish` 调用方。

#### Scenario: 异步执行不阻塞 publish
- **WHEN** publish 一个事件且监听器内存在 `Thread.sleep(500)`
- **THEN** publish 调用立即返回（< 100ms），监听器在后台完成

#### Scenario: 监听器异常被吞没
- **WHEN** `onUserSpokenWarmup` 内部抛出 RuntimeException
- **THEN** publish 调用方不受影响，异常由 `AsyncUncaughtExceptionHandler` 处理（默认日志）

#### Scenario: 审计日志含三字段
- **WHEN** publish `UserSpokenEvent("s1", 1L, "买跑鞋", t)`
- **THEN** 日志中存在 INFO 级别记录，文本包含 `s1`、`1`、`买跑鞋`

### Requirement: EventDebugController POST /api/v1/event/user-spoken
系统 SHALL 提供 `POST /api/v1/event/user-spoken` HTTP 接口用于联调事件总线。

约束：
- 请求体为专用 record，含 `sessionId`、`userId`、`utterance` 三字段（禁止使用 `Map<String,Object>`）。
- `sessionId` 与 `utterance` MUST 校验非空；`userId` MAY 为 null。
- Controller MUST 调用 `voiceEventPublisher.publish(new UserSpokenEvent(sessionId, userId, utterance, System.currentTimeMillis()))`，然后立即返回 `ApiResult.ok(null)` 或 `ApiResult.ok` 带空 payload。
- 响应 MUST 为 `ApiResult` 包装。

#### Scenario: 调试接口正常调用
- **WHEN** POST `/api/v1/event/user-spoken` 传入 `{"sessionId":"s1","userId":1,"utterance":"买跑鞋"}`
- **THEN** 返回 200；后续日志中能看到 `onUserSpokenAudit` 的 INFO 输出

#### Scenario: utterance 为空校验失败
- **WHEN** POST 传入 `{"sessionId":"s1","userId":1,"utterance":""}`
- **THEN** 返回 400
