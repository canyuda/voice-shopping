## Context

当前 Orchestrator 的 `ORDER_CONFIRM` 分支是占位话术，下单全链路尚未打通。`OrderService` 已存在但只承担"查询门面"（query-only，CLAUDE.md "主体强制过滤原则"），订单写入逻辑、库存扣减、画像回流、长期记忆触发都还是空白。

现状关键事实（评估方案时必须对齐）：

- `SessionState.lastRecommendations` 在 PG 是 `JSONB`，Hibernate 反序列化成 `Map<String, Object>`；业务层通过 `objectMapper.convertValue(map, LastRecommendationsSnapshot.class)` 解构为 `{ items, productIds, minPrice, maxPrice }`
- `OrderRecord` 实体**没有**单品字段（productId/skuCode/quantity/unitPrice），订单项放在 `items JSONB`（`List<OrderItemJson>`）
- `UserBehaviorSink.onPurchased` 已经挂了 `@Async @EventListener UserPurchasedEvent` → 写画像；但**目前没人发布这个事件**，等价于死代码
- `LongTermMemoryWriter.flushOnSessionEnd` 通过短期记忆作为幂等闸口，可被多次安全调用
- `ProductRepository` 是 Spring Data JPA 接口，新增原子扣库存需要 `@Modifying @Query`
- Orchestrator 现有 `readLastRecommendations(state)` 工具方法已经在做 JSONB→Snapshot 的反序列化，订单分支应复用

约束：

- CLAUDE.md "禁止硬编码"：Redis key 前缀、TTL、序数词单位词列表走配置/常量类
- CLAUDE.md "禁止吞掉异常"：库存不足、商品不存在、商家不匹配都 fail fast
- CLAUDE.md "接口请求体禁止使用 `Map<String, Object>`"：`PendingOrder` 用 Java Record
- CLAUDE.md "主体强制过滤原则"：现有 `OrderService` 的查询方法签名（`userId` / `merchantId` 显式入参）不能改

## Goals / Non-Goals

**Goals:**
- 实现一次性"语音 → 引用解析 → 预览 → 二次确认 → 落单 → 画像回流"的完整闭环
- PG 原子扣库存，从 SQL 层杜绝超卖（无需引入分布式锁）
- 下单事务与画像/长期记忆解耦：事务回滚不污染画像；事务提交后异步回流
- ORDER_CONFIRM phase 不是"无限粘性"——pending 失效后自动回归正常意图链路
- 复用既有 `UserPurchasedEvent`，不引入并行事件类

**Non-Goals:**
- 收货地址收集、真实支付通道、多商品下单、分布式锁库存软占用
- ASR 流式接入下单流程（仍由 Orchestrator handle 单次 utterance 驱动）
- 退款 / 取消订单的状态机（V2）
- 跨商家比价后下单（preview 已强制单商家边界）

## Decisions

### D1: OrderService 单类承载 query + write（不拆 OrderPlacementService）

**选项 A（采纳）**：在现有 `OrderService` 加 `preview / confirm / cancel`，与 `getForUser / listMine / listForMerchant` 并存。
**选项 B（放弃）**：拆 `OrderPlacementService` 保持 query-only 边界。

**理由**：用户明确拍板"不拆混在一起"。代价是 `OrderService` 同时承担查询与写入两种语义，需要通过方法注释和参数签名区分。收益是模块依赖图少一个节点。
查询方法主体强制过滤的合规约束**保持不变**——`getForUser / listMine` 的 `userId` 显式入参仍是 Repository 派生查询的入口约束，写入方法新增不影响这条护栏。

### D2: 库存扣减 = PG 原子 UPDATE（不引入 Redis 软占用）

```sql
UPDATE product SET stock = stock - :qty
WHERE id = :pid AND stock >= :qty AND deleted_at IS NULL
```

JPA：

```java
@Modifying
@Query(value = "UPDATE product SET stock = stock - :qty " +
               "WHERE id = :productId AND stock >= :qty AND deleted_at IS NULL",
       nativeQuery = true)
int decrementStock(@Param("productId") Long productId, @Param("qty") int qty);
```

受影响行数 = 0 → 抛 `IllegalStateException("库存不足")`，由 GlobalExceptionHandler 翻成 400。

**理由**：
- preview 阶段不做真实占用——允许两个用户都看到"剩 1 件"，confirm 阶段只一人能赢，对语音 demo 颗粒度匹配
- 单条 UPDATE 在行级锁里原子完成，比 `findById → if → save` 两步事务安全得多
- 不依赖额外中间件，回归到 PG 这一个真相源

**风险**：preview 后到 confirm 之间真实库存可能被另一会话扣走 → confirm 时报"库存不足"。文案兜底"刚被别人抢走了，要不要看看类似的？"，并把 phase 回退 RECOMMEND。

### D3: PendingOrder 存 Redis + JSON 序列化

- Key: `vs:pending_order:{sessionId}`（登记到 `RedisKeys` 常量）
- Value: `PendingOrder` record 的 JSON
- TTL: `voice-shopping.order.pending-ttl-seconds`（默认 600）—— **不硬编码**
- 写入：`StringRedisTemplate.opsForValue().set(key, json, ttl)`
- 读取：miss 直接返回 null（让 Orchestrator 走"过期回退"路径）
- 删除：`confirm` 成功、`cancel` 显式调用、TTL 到期三条路径

**为什么不持久化到 PG**：pending 不需要审计、不需要跨进程查询、生命周期 10 分钟。Redis 是颗粒度最匹配的存储。

### D4: 下单成功事件链——`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`

```java
@Transactional
public OrderRecord confirm(String sessionId) {
    PendingOrder po = pendingStore.get(sessionId);                    // fail fast: null/expired
    int rows = productRepository.decrementStock(po.productId(), po.quantity());
    if (rows == 0) throw new IllegalStateException("库存不足");
    // 防 D5 越权
    Product p = productRepository.findById(po.productId()).orElseThrow();
    if (!p.getMerchantId().equals(po.merchantId())) throw new IllegalStateException("商品归属异常");
    OrderRecord saved = orderRecordRepository.save(buildRecord(po, p));
    pendingStore.remove(sessionId);
    eventPublisher.publishEvent(new UserPurchasedEvent(po.userId(), p.getCategory(), p.getBrand(), po.totalAmount(), sessionId));
    return saved;
}
```

监听端改造：

```java
@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onPurchased(UserPurchasedEvent event) { ... }   // UserBehaviorSink

@Async
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void onPurchasedFlushLongTerm(UserPurchasedEvent event) { ... }   // LongTermMemoryWriter（新增）
```

**理由**：
- 事务回滚（库存扣减失败、orderRepo.save 失败）→ 事件不会发出 → 画像不被污染、长期记忆不会过早 flush
- `@Async` 让监听器在另一线程跑 → 不阻塞 confirm 的响应链路（语音端等 TTS 输出）
- `UserPurchasedEvent` 复用已有 DTO，但需扩展 `sessionId` 字段（**详见 D8**），让 `LongTermMemoryWriter` 知道 flush 哪个 session

**风险**：进程崩溃在 AFTER_COMMIT 之前 → 事件丢失，画像/长期记忆不更新但订单已落库。对账层后续可加"已下单但未回流"的扫描任务。本次先不做，记录为 Open Question。

### D5: preview 阶段强制商家边界校验

`SessionScope` 决定了本会话能访问哪些商家的商品；ScopeFilter 在检索阶段挡过一次，但下单是另一条入口（用户直接说商品名）。`OrderService.preview` 必须再校验一次：

```java
public PendingOrder preview(String sessionId, Long userId, Long productId, int qty) {
    Product p = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("商品不存在"));
    SessionScope scope = sessionScopeService.resolve(sessionId);
    if (!scope.allowedMerchantIds().contains(p.getMerchantId())) {
        throw new ForbiddenException("该商品不在当前会话范围内");
    }
    if (p.getStock() < qty) throw new IllegalStateException("库存不足");
    ...
}
```

**理由**：defense in depth。多商家隔离 spec 把"主体强制过滤"作为合规底线，下单入口不豁免。

### D6: 序数词识别 — 改进版正则 + 排除规则

```java
// 单位词扩展：款 / 个 / 种 / 号 / 件 / 双
// 数字扩展：1-9 + 一二三四五六七八九 + 十
private static final Pattern ORDINAL =
    Pattern.compile("第?([一二三四五六七八九十1-9])(?:款|个|种|号|件|双)?");

// 排除：倒数第几 / 最后第几 直接走"最后"逻辑，不走索引
private static final Pattern REVERSE = Pattern.compile("倒数|最后第");
```

resolve 流程：
1. 命中 REVERSE → 走"最后那款"语义 → 返回最后一件
2. 命中"最后"/"末" → 最后一件
3. 命中"第一"/"开头"/"首" → 第一件
4. 命中"中间" → `size/2`
5. ORDINAL 匹配且未命中 REVERSE → 转 index；越界返回 empty（让 EmotionAgent 追问）
6. 全部 miss → empty

**理由**：从坑 5 改进。原伪码会把"倒数第二"识别成第 2 件 → 下错单。

### D7: containsYes / containsNo — NO 优先 + 短语而非单字

```java
private boolean containsNo(String s) {
    if (s == null) return false;
    return List.of("不要", "不行", "不用", "算了", "取消", "等等", "再想想", "先不", "别下")
            .stream().anyMatch(s::contains);
}
private boolean containsYes(String s) {
    if (s == null) return false;
    // NO 必须先排除，避免 "好像不太对" 误判
    if (containsNo(s)) return false;
    return List.of("确认", "下单吧", "就要这个", "就这", "可以", "OK", "ok", "买了", "嗯就这")
            .stream().anyMatch(s::contains);
}
```

**理由**：
- "好" "对" "嗯" 单字歧义太高，从匹配集合移除
- NO 短路 YES：用户说"好像不太对"应当走 NO 路径，宁可错杀让用户复述
- 都没命中 → 反问"那你是确认要这款还是不要？"（保持伪码行为）

### D8: UserPurchasedEvent 需要扩展 sessionId 字段

当前 `UserPurchasedEvent` 字段是 `userId / category / brand / amount`。`LongTermMemoryWriter.flushOnSessionEnd(sessionId, userId)` 需要 sessionId，因此 event 需要扩展。

**选项 A（采纳）**：在 `UserPurchasedEvent` 加一个可空 `sessionId` 字段
**选项 B（放弃）**：新发一个 `OrderConfirmedEvent`，让 LongTermMemoryWriter 监听新事件

理由：用户明确说"复用既有 UserPurchasedEvent，不新造"。`sessionId` 对老路径（如果未来有从订单管理后台手工补录）是 null，老的 `UserBehaviorSink.onPurchased` 不读 sessionId 不受影响。

### D9: ORDER_CONFIRM phase 短路 + 过期回退

```java
public EmotionResult handle(...) {
    SessionState state = sessionStateService.load(sessionId).orElseGet(...);

    // 短路：phase==ORDER_CONFIRM 时跳过 IntentService
    if ("ORDER_CONFIRM".equals(state.getPhase())) {
        BranchOutcome outcome = handleOrderConfirm(sessionId, userId, state, utterance);
        if (outcome != null) {
            // 正常走订单分支
            EmotionResult safe = complianceChecker.ensureCompliant(sessionId, userId, outcome.reply());
            writeShortTermMemory(...);
            persistState(state, IntentEnum.ORDER_CONFIRM, ..., outcome);
            return safe;
        }
        // outcome == null 表示 pending 过期且无法 resolve → 回退 RECOMMEND 重跑 dispatch
        state.setPhase("RECOMMEND");
        sessionStateService.save(state);
        // fall through 走 IntentService 正常路径
    }

    // 原有 IntentService → reviseIntent → dispatch 链路
    ...
}
```

`handleOrderConfirm` 返回 `null` 的唯一条件：pending=null 且 resolver 解析失败。其他情况都返回有效 BranchOutcome（YES/NO/反问"哪一款"）。

**理由**：避免用户在 ORDER_CONFIRM phase 说"再看看别的"被卡死在"是哪一款？"的死循环。

### D10: pending 在 phase=ORDER_CONFIRM 但 resolve 成功的特殊路径

进入 `handleOrderConfirm` 时：
- 有 pending → YES / NO / 反问（伪码原版）
- 无 pending 但 resolve 成功（用户改主意了，"算了我要第一款"）→ 当作"无 pending → 新 preview"路径，覆盖式新建 PendingOrder
- 无 pending 且 resolve 失败 → 返回 null，走 D9 回退

**理由**：用户在 pending 过期之后立刻说"我要第一款"，应当能顺畅进入新一轮预览，不应被 phase 卡死。

## Risks / Trade-offs

| Risk | Mitigation |
|------|-----------|
| preview 后 confirm 前库存被抢 → confirm 失败 | PG 原子 UPDATE 必然失败，文案兜底"刚被别人抢走了"，phase 回退 RECOMMEND |
| 进程崩溃在 commit 后但事件未异步执行 → 画像/长期记忆未更新 | 记录为 Open Question；后续加扫描任务"按 order_record.created_at 补回流" |
| 用户连续说"第二款" "第三款" 但 pending 还在 | 当前实现 YES/NO 二分；新的 productId 会被 containsYes/containsNo 都判 false → 反问。**接受这个行为**，等下个版本支持 pending 覆盖 |
| 序数词正则误判（如"第二天到"被识别成第 2 件） | 单位词列表偏窄（款/个/种/号/件/双），不含"天"。Test case 兜住边界 |
| `decrementStock` native query 与 Hibernate 一级缓存不一致 | confirm 内 UPDATE 后立即 publish event，不再用本事务内 Product 实体；如必须读 stock，加 `entityManager.refresh(product)` |
| ForbiddenException 与 IllegalStateException 在 demo 期统一翻 400/403，文案泄露资源存在性 | preview 阶段商家不匹配抛 `ForbiddenException`（403）—— 因为商品 id 是用户/LLM 自己说的，本来就是 public 的，不存在信息泄露 |

## Migration Plan

**部署**：
1. 合 Repository / DTO / Service / Orchestrator / 监听器一次性上线（功能开关 `voice-shopping.order.enabled`，默认 true）
2. 上线后第一周用 ChatDebugController 灰度验证：preview / confirm / cancel / 过期回退 / 库存竞争 / 商家边界
3. 监控指标：`voice.shopping.order.confirm`（Timer，tag=`outcome` ∈ {success, stock_exhausted, forbidden, pending_expired}）

**回滚**：
- 关闭 `voice-shopping.order.enabled` → ORDER_CONFIRM 分支回到占位话术（保留旧分支代码作 fallback）
- 数据回滚：order_record 表数据保留，stock 字段不回滚

## Open Questions

1. 进程崩溃在 commit 后但 AFTER_COMMIT 事件未触发的对账机制（先记，下一版补扫描任务）
2. 多商品下单（"我两双都要"）的 PendingOrder schema 是否要预留 `items: List<...>` 字段，本次先 `quantity: int` 单件
3. 是否需要"待支付"中间态（status=`CREATED` → `PAID`）？本次直接 PAID，对接真实支付时再拆
