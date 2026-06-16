# order-placement-flow

## Purpose

订单下单子状态机能力。基于 ORDER_CONFIRM 意图与 `session_state.phase=ORDER_CONFIRM` 的短路入口，实现「引用项解析 → 预览单生成 → YES/NO 二次确认 → 真实落单或取消」的完整闭环。预览态订单暂存 Redis（避免污染 PG），落单走 `@Transactional` + 原子扣库存（`UPDATE ... WHERE stock >= qty`）防超卖，事务提交后通过 `UserPurchasedEvent` 触发画像与长期记忆回流。

## Requirements

### Requirement: OrderReferenceResolver 引用项解析

系统 SHALL 在 `com.voiceshopping.business.order` 包下提供 `OrderReferenceResolver`，将用户口语化的指代翻译成 `lastRecommendations` 中的 `productId`。

输入：`LastRecommendationsSnapshot snapshot, String utterance`，输出 `Optional<Long>`（解析失败时为 empty）。

解析规则按以下优先级执行：

1. **反向指代排除**：utterance 命中 `倒数` 或 `最后第` → 返回最后一件 `productId`
2. **位置语义**：utterance 含 `最后` / `末` → 最后一件；含 `第一` / `开头` / `首` → 第一件；含 `中间` → `size/2` 位置
3. **序数词正则**：`第?([一二三四五六七八九十1-9])(?:款|个|种|号|件|双)?`
   - 命中后中文/数字转 0-based index
   - 越界返回 empty
4. 全部 miss → empty

#### Scenario: 第二款映射到 index=1
- **WHEN** snapshot.productIds = [101L, 202L, 303L]，utterance = "我要第二款"
- **THEN** resolve 返回 `Optional.of(202L)`

#### Scenario: 阿拉伯数字与中文数字等价
- **WHEN** snapshot.productIds = [10L, 20L, 30L]，utterance = "就第3个吧"
- **THEN** resolve 返回 `Optional.of(30L)`

#### Scenario: 倒数第二走"最后"语义而非 index=1
- **WHEN** snapshot.productIds = [10L, 20L, 30L]，utterance = "倒数第二的那个"
- **THEN** resolve 返回 `Optional.of(30L)`（最后一件，不是 20L）

#### Scenario: 单位词扩展支持"双"
- **WHEN** snapshot.productIds = [11L, 22L]，utterance = "刚才那第一双"
- **THEN** resolve 返回 `Optional.of(11L)`

#### Scenario: 越界返回 empty
- **WHEN** snapshot.productIds = [10L, 20L]，utterance = "第五款"
- **THEN** resolve 返回 `Optional.empty()`

#### Scenario: lastRecommendations 为空返回 empty
- **WHEN** snapshot.productIds = []，utterance = "第一款"
- **THEN** resolve 返回 `Optional.empty()`

#### Scenario: utterance 不含任何指代返回 empty
- **WHEN** utterance = "我饿了"
- **THEN** resolve 返回 `Optional.empty()`

---

### Requirement: PendingOrderStore Redis 暂存

系统 SHALL 在 `com.voiceshopping.business.order` 包下提供 `PendingOrderStore`，将预览态订单暂存至 Redis。

约束：
- Key 模板：`vs:pending_order:{sessionId}`，常量定义在 `RedisKeys`，**不得在业务代码硬编码字面量**
- TTL：通过 `voice-shopping.order.pending-ttl-seconds`（默认 600）配置注入，**不得硬编码**
- 序列化：通过共享 `ObjectMapper` 转 JSON，存为 String
- API：`put(PendingOrder)` / `get(String sessionId): PendingOrder | null` / `remove(String sessionId)`

`PendingOrder` 为 Java Record，定义在 `com.voiceshopping.common.dto.order` 包：

```java
public record PendingOrder(
    String sessionId,
    Long userId,
    Long merchantId,
    Long productId,
    String productName,
    String skuCode,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal totalAmount
) {}
```

#### Scenario: put 写入并设置 TTL
- **WHEN** `put(PendingOrder("sess-1", 100L, 1L, 8821L, "Asics", "AS-001", 1, 479.00, 479.00))`
- **THEN** Redis key `vs:pending_order:sess-1` 含 JSON 序列化结果，TTL 介于 0 与配置值之间

#### Scenario: get 反序列化已写入的订单
- **WHEN** 已 put 上述订单后调用 `get("sess-1")`
- **THEN** 返回的 PendingOrder 等值于原始 record

#### Scenario: get miss 返回 null
- **WHEN** key 不存在或已过期
- **THEN** `get("sess-x")` 返回 null（**不**抛异常）

#### Scenario: remove 幂等
- **WHEN** key 不存在时调用 `remove("sess-x")`
- **THEN** 方法静默成功

#### Scenario: TTL 不硬编码
- **WHEN** 配置 `voice-shopping.order.pending-ttl-seconds=120`
- **THEN** 新写入的 key 实际 TTL ≤ 120 秒

---

### Requirement: OrderService.preview 生成预览单

系统 SHALL 在 `OrderService` 新增 `preview(String sessionId, Long userId, Long productId, int quantity)` 方法，返回 `PendingOrder`。

流程：
1. `productRepository.findById(productId)`；不存在 → `IllegalArgumentException("商品不存在")`
2. 校验 `product.merchantId ∈ SessionScope.allowedMerchantIds(sessionId)`；不在 → `ForbiddenException("该商品不在当前会话范围内")`
3. 校验 `product.stock >= quantity`；不足 → `IllegalStateException("库存不足")`
4. 构造 `PendingOrder`，`unitPrice = product.price`，`totalAmount = unitPrice * quantity`
5. `pendingOrderStore.put(po)`（覆盖式：同 sessionId 已有 pending 直接覆盖）
6. 返回 `po`

preview MUST NOT 真实扣减库存。

#### Scenario: 合法 preview 生成预览单
- **WHEN** product 库存 5，scope 含该 merchant，调用 preview(sessionId, userId, productId, 1)
- **THEN** 返回 PendingOrder，Redis 已写入，product.stock 仍为 5

#### Scenario: 商品不存在 fail fast
- **WHEN** productId 不存在
- **THEN** 抛 IllegalArgumentException("商品不存在")，Redis 无写入

#### Scenario: 跨商家越权抛 ForbiddenException
- **WHEN** product.merchantId 不在 SessionScope.allowedMerchantIds 内
- **THEN** 抛 ForbiddenException("该商品不在当前会话范围内")，Redis 无写入

#### Scenario: 库存不足抛 IllegalStateException
- **WHEN** product.stock = 0，quantity = 1
- **THEN** 抛 IllegalStateException("库存不足")，Redis 无写入

#### Scenario: 已有 pending 被覆盖
- **WHEN** 同 sessionId 调用 preview 两次，第二次传不同 productId
- **THEN** Redis 中保留第二次的 PendingOrder

---

### Requirement: ProductRepository 原子扣库存

系统 SHALL 在 `ProductRepository` 接口提供原子扣库存方法：

```java
@Modifying
@Query(value = "UPDATE product SET stock = stock - :qty " +
               "WHERE id = :productId AND stock >= :qty AND deleted_at IS NULL",
       nativeQuery = true)
int decrementStock(@Param("productId") Long productId, @Param("qty") int qty);
```

返回值 = 0 表示无可扣库存或商品已删除。MUST 在 `@Transactional` 上下文调用。

#### Scenario: 库存充足扣减成功
- **WHEN** stock = 5，调用 `decrementStock(pid, 1)`
- **THEN** 返回 1，PG 中 stock = 4

#### Scenario: 库存不足返回 0 且不修改
- **WHEN** stock = 0，调用 `decrementStock(pid, 1)`
- **THEN** 返回 0，PG 中 stock = 0

#### Scenario: 已软删除商品扣减失败
- **WHEN** deleted_at IS NOT NULL，stock = 5，调用 decrementStock
- **THEN** 返回 0

#### Scenario: 并发扣减不超卖
- **WHEN** stock = 1，两个并发事务各调用 decrementStock(pid, 1)
- **THEN** 一个返回 1、一个返回 0；PG 终值 stock = 0

---

### Requirement: OrderService.confirm 真实落单

系统 SHALL 在 `OrderService` 新增 `@Transactional confirm(String sessionId)` 方法，返回 `OrderRecord`。

流程：
1. `pendingStore.get(sessionId)`；null → `IllegalStateException("没有待确认订单，或已过期")`
2. `productRepository.decrementStock(po.productId, po.quantity)`；返回 0 → `IllegalStateException("库存不足")`
3. `productRepository.findById(po.productId)`；二次校验 `merchantId` 与 `po.merchantId` 一致；不一致 → `IllegalStateException("商品归属异常")`
4. 构造 `OrderRecord`：
   - `orderNo = UUID.randomUUID().toString().replace("-", "")`
   - `items = List.of(new OrderItemJson(po.productId, po.productName, po.unitPrice, po.quantity))`
   - `totalAmount = po.totalAmount`
   - `status = "PAID"`
   - `agentAttribution = true`
   - `sourceIntent = "ORDER_CONFIRM"`
5. `orderRecordRepository.save(record)`
6. `pendingStore.remove(sessionId)`
7. `applicationEventPublisher.publishEvent(new UserPurchasedEvent(po.userId, product.category, product.brand, po.totalAmount, sessionId))`（**在事务内发布**）
8. 返回 saved `OrderRecord`

#### Scenario: 完整 confirm 落单成功
- **WHEN** pending 存在，库存充足，merchantId 匹配
- **THEN** 返回 OrderRecord，status=PAID，agent_attribution=true，items 含一条线项；product.stock 减 1；Redis pending 已删除；UserPurchasedEvent 已发布

#### Scenario: pending 不存在抛 IllegalStateException
- **WHEN** `pendingStore.get(sessionId) == null`
- **THEN** 抛 IllegalStateException("没有待确认订单，或已过期")；无任何写入

#### Scenario: 库存被抢空抛"库存不足"
- **WHEN** preview 之后、confirm 之前，另一会话抢光库存
- **THEN** `decrementStock` 返回 0，抛 IllegalStateException("库存不足")；orderRecord 未写入；Redis pending **不删除**（让用户的下次确认/取消能正常走）

#### Scenario: 商品归属异常事务回滚
- **WHEN** PendingOrder.merchantId ≠ Product.merchantId（被恶意篡改）
- **THEN** 抛 IllegalStateException("商品归属异常")；由于在 decrementStock 之后校验，事务回滚使 stock 恢复

#### Scenario: 事件在事务内发布
- **WHEN** confirm 成功返回
- **THEN** `applicationEventPublisher.publishEvent` 已被调用一次，事件 payload 含 sessionId/userId/category/brand/totalAmount

---

### Requirement: OrderService.cancel 取消预览单

系统 SHALL 在 `OrderService` 新增 `cancel(String sessionId)` 方法。

实现：`pendingStore.remove(sessionId)`。幂等，无返回值。

#### Scenario: cancel 删除 Redis pending
- **WHEN** pending 存在时调用 `cancel(sessionId)`
- **THEN** Redis key `vs:pending_order:{sessionId}` 被删除

#### Scenario: 重复 cancel 不报错
- **WHEN** pending 不存在时调用 `cancel(sessionId)`
- **THEN** 方法静默返回

---

### Requirement: UserPurchasedEvent 扩展 sessionId 字段

系统 SHALL 在 `com.voiceshopping.business.behavior.UserPurchasedEvent` 增加可空 `String sessionId` 字段，用于让 `LongTermMemoryWriter` 知道 flush 哪个会话的短期记忆。

旧路径（如未来后台手工补录）允许 `sessionId == null`，`UserBehaviorSink.onPurchased` MUST NOT 读取此字段。

#### Scenario: confirm 发布的事件携带 sessionId
- **WHEN** OrderService.confirm 触发事件
- **THEN** `event.getSessionId()` 等于当前 sessionId

#### Scenario: 旧路径 sessionId 为 null 时画像照常更新
- **WHEN** UserPurchasedEvent 被 sessionId=null 触发
- **THEN** UserBehaviorSink.onPurchased 正常完成画像写入，不抛 NPE

---

### Requirement: LongTermMemoryWriter 监听 UserPurchasedEvent

系统 SHALL 在 `LongTermMemoryWriter` 注册以下监听方法：

```java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onPurchasedFlushLongTerm(UserPurchasedEvent event) {
    if (event.getSessionId() == null) return;
    flushOnSessionEnd(event.getSessionId(), event.getUserId());
}
```

MUST 仅在事务提交后异步执行，事务回滚时 MUST NOT 触发。

#### Scenario: confirm 成功触发长期记忆 flush
- **WHEN** OrderService.confirm 事务提交成功
- **THEN** `LongTermMemoryWriter.flushOnSessionEnd(sessionId, userId)` 异步被调用一次

#### Scenario: confirm 事务回滚不触发 flush
- **WHEN** OrderService.confirm 在 publishEvent 之后但 commit 之前抛异常
- **THEN** `flushOnSessionEnd` 未被调用

#### Scenario: sessionId 为 null 跳过
- **WHEN** 事件 sessionId=null
- **THEN** `flushOnSessionEnd` 未被调用，方法静默返回

---

### Requirement: Orchestrator handleOrderConfirm 分支

系统 SHALL 在 `OrchestratorService` 提供包私有方法 `handleOrderConfirm(String sessionId, Long userId, SessionState state, String utterance): BranchOutcome`，处理订单确认子状态机。

流程：

1. 读 `pendingOrderStore.get(sessionId)`
2. **pending 存在**：
   - `containsNo(utterance)` 为 true → `orderService.cancel(sessionId)`，phase=`RECOMMEND`，返回话术 `"好的，没给你下。想再聊点别的还是换款看看？"`
   - `containsYes(utterance)` 为 true →
     - try `orderService.confirm(sessionId)`：
       - 成功：phase=`ENDED`，返回话术 `"下单成功，订单尾号 {orderNo.substring(0,6)}，1-2 天送达。还有想看的吗？"`
       - 抛 `IllegalStateException("库存不足")`：phase=`RECOMMEND`，pending 已不在 Redis（confirm 抛前未删），返回话术 `"不好意思，刚才那件被抢走了，要不要看看类似的？"`
       - 其他异常：上抛由 GlobalExceptionHandler 处理
   - 都不命中 → phase 保持 `ORDER_CONFIRM`，返回话术 `"那你是确认要这款还是不要？"`
3. **pending 不存在**：
   - 反序列化 `state.lastRecommendations` → snapshot
   - `referenceResolver.resolve(snapshot, utterance)`
   - 解析成功 → `orderService.preview(sessionId, userId, pid, 1)`，phase=`ORDER_CONFIRM`，返回话术 `"好，帮你准备下单：{productName}，¥{unitPrice}，一共 {totalAmount} 元。确认下单吗？"`
   - 解析失败 → 返回 `null`（让 handle 主流程走"过期回退"路径，见 phase 短路 requirement）

#### Scenario: pending 存在 + YES → 成功落单
- **WHEN** pending 存在，utterance="确认下单"
- **THEN** `orderService.confirm` 被调用，返回 EmotionResult speechText 以 "下单成功，订单尾号 " 开头，phase=ENDED

#### Scenario: pending 存在 + NO → 取消回到 RECOMMEND
- **WHEN** pending 存在，utterance="算了不要了"
- **THEN** `orderService.cancel` 被调用，phase=RECOMMEND，speechText 含"没给你下"

#### Scenario: pending 存在 + 模糊 → 再次反问
- **WHEN** pending 存在，utterance="嗯，这个怎么样"
- **THEN** phase 保持 ORDER_CONFIRM，speechText 含"确认要这款还是不要"

#### Scenario: pending 存在 + YES + 库存被抢 → 友好回退
- **WHEN** pending 存在，utterance="确认"，但 `confirm` 抛"库存不足"
- **THEN** phase=RECOMMEND，speechText 含"刚才那件被抢走了"

#### Scenario: 无 pending + 解析成功 → 新建 preview
- **WHEN** pending 不存在，state.lastRecommendations 含 3 件，utterance="第二款"
- **THEN** `orderService.preview` 被调用 productId=index1，phase=ORDER_CONFIRM，speechText 含"确认下单吗"

#### Scenario: 无 pending + 解析失败 → 返回 null
- **WHEN** pending 不存在，utterance="我饿了"
- **THEN** `handleOrderConfirm` 返回 null（由 handle 主流程触发回退）

---

### Requirement: containsYes / containsNo 优先级规则

`OrchestratorService` 中的 `containsYes / containsNo` MUST 满足：

- `containsNo`：utterance 命中以下任一短语：`不要 / 不行 / 不用 / 算了 / 取消 / 等等 / 再想想 / 先不 / 别下`
- `containsYes`：先用 `containsNo` 短路（命中即返回 false），再检查 utterance 是否命中：`确认 / 下单吧 / 就要这个 / 就这 / 可以 / OK / ok / 买了 / 嗯就这`
- 短语列表 MUST 定义为常量 List，**不得**散落硬编码字面量

#### Scenario: "好像不太对" 走 NO 路径
- **WHEN** utterance = "好像不太对"
- **THEN** containsYes=false，containsNo=true

#### Scenario: 单字"好"不命中 YES
- **WHEN** utterance = "好"
- **THEN** containsYes=false，containsNo=false

#### Scenario: "确认下单" 命中 YES
- **WHEN** utterance = "确认下单吧"
- **THEN** containsYes=true，containsNo=false

#### Scenario: 大小写 ok 都命中
- **WHEN** utterance ∈ {"OK", "ok"}
- **THEN** containsYes=true

---

### Requirement: 订单配置项

系统 SHALL 暴露以下 Spring 配置项：

| Key | 类型 | 默认值 | 用途 |
|-----|------|--------|------|
| `voice-shopping.order.enabled` | boolean | true | 关闭后 handleOrderConfirm 立即回到占位话术（rollback 开关） |
| `voice-shopping.order.pending-ttl-seconds` | int | 600 | PendingOrder Redis TTL |

#### Scenario: 关闭开关回到占位话术
- **WHEN** `voice-shopping.order.enabled=false`，意图为 ORDER_CONFIRM
- **THEN** 返回 EmotionResult speechText = "好，给你下单（完整下单逻辑后续补）"

#### Scenario: 自定义 TTL 生效
- **WHEN** `voice-shopping.order.pending-ttl-seconds=120` 且 preview 成功
- **THEN** Redis key TTL ≤ 120 秒
