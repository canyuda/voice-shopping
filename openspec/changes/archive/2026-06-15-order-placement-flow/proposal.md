## Why

当前 Orchestrator 的 `ORDER_CONFIRM` 分支只是回了一句占位话术 ("好，给你下单（完整下单逻辑后续补）")，整个语音购物链路从"推荐 → 比较 → 下单"始终断在最后一步，无法形成业务闭环。下单是衡量 AI 推荐 ROI（`agent_attribution`）的唯一可观测信号，也是触发跨会话长期记忆回流的"会话有效结束"标志——没有这一步，画像的购买侧权重永远是 0，推荐质量评估失去最强信号。

## What Changes

- 新增 `OrderReferenceResolver`：把"第二款 / 刚才的那双"等指代翻译成 `lastRecommendations` 中的 `productId`
- 新增 `PendingOrderStore`：Redis 暂存预览态订单（key=`vs:pending_order:{sessionId}`, TTL=10min）
- 扩展现有 `OrderService`（**不拆**）：在保留 query-only 主体强制过滤接口的基础上，新增 `preview / confirm / cancel` 写入方法
- `ProductRepository` 新增 PG 原子扣库存方法 `UPDATE product SET stock = stock - :qty WHERE id = :pid AND stock >= :qty`，从 SQL 层杜绝超卖
- `OrchestratorService`：
  - `handle()` 入口短路——`phase == ORDER_CONFIRM` 时跳过 IntentService 直接走订单分支
  - 实现 `handleOrderConfirm(sessionId, userId, state, utterance)`：pending 存在时判 YES/NO；不存在时 resolve 引用 → preview → 二次确认
  - pending 过期回退：phase=ORDER_CONFIRM 但 Redis pending=null 且 resolve 失败时，phase 重置为 RECOMMEND 并重跑 dispatch，避免"无限粘性"
- 下单成功在事务内 `publishEvent(UserPurchasedEvent)`；`UserBehaviorSink.onPurchased` 改为 `@TransactionalEventListener(phase=AFTER_COMMIT)` + `@Async`，保证回滚不污染画像
- `LongTermMemoryWriter.flushOnSessionEnd` 同样改为 AFTER_COMMIT 监听同一事件，下单成功 = 会话有效结束 → 跨会话偏好回流

## Capabilities

### New Capabilities
- `order-placement-flow`: 订单下单状态机与闭环 —— 引用解析、PendingOrder 暂存、PG 原子扣库存、二次确认、下单成功事件回流

### Modified Capabilities
- `orchestrator-service`: `ORDER_CONFIRM` 分支从占位话术升级为完整子状态机；新增 phase 短路与 pending 过期回退规则
- `user-behavior-sink`: `onPurchased` 监听语义从 `@EventListener`（同步）变为 `@TransactionalEventListener(AFTER_COMMIT) + @Async`，并由 `OrderService.confirm()` 真实触发

## Impact

**代码**
- 新增：
  - `voice-shopping-business/.../order/OrderReferenceResolver.java`
  - `voice-shopping-business/.../order/PendingOrderStore.java`
  - `voice-shopping-common/.../dto/order/PendingOrder.java`（record）
- 修改：
  - `voice-shopping-business/.../order/OrderService.java`（新增 preview/confirm/cancel + `@Transactional` 写入路径）
  - `voice-shopping-business/.../orchestrator/OrchestratorService.java`（短路、`handleOrderConfirm`、pending 过期回退）
  - `voice-shopping-business/.../behavior/UserBehaviorSink.java`（`@EventListener` → `@TransactionalEventListener(AFTER_COMMIT)`）
  - `voice-shopping-business/.../memory/LongTermMemoryWriter.java`（注册 AFTER_COMMIT 监听器接收 `UserPurchasedEvent`）
  - `voice-shopping-infrastructure/.../repository/ProductRepository.java`（新增 `@Modifying @Query` 原子扣库存方法）
- 单元/集成测试：`OrchestratorServiceTest`、`OrderServiceTest`、`OrderReferenceResolverTest` 新增分支

**数据**
- 不涉及 Flyway 迁移 —— `order_record` / `product` 表结构 V1 已就绪
- 新增 Redis key 规范：`vs:pending_order:{sessionId}`（String + JSON, TTL 10min），登记到 `RedisKeys` 常量

**外部依赖**
- 无新依赖；继续使用 `StringRedisTemplate` + `ObjectMapper`
- 复用既有 `UserPurchasedEvent`（**不**新造 `ProductPurchasedEvent`）

**显式不在本次范围**
- 收货地址收集（demo 阶段 `receiver_*` 字段允许 NULL）
- 真实支付通道（`status` 直接置 `PAID`，留待后续扩展）
- 多商品下单（本次只支持单件下单，`quantity` 写死 1）
- ASR 接入下单流程（仍由 Orchestrator handle 单次 utterance 驱动）
