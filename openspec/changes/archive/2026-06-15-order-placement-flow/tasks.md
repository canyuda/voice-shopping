## 0. 前置修正（实施期发现的接口偏差）

> 实施期勘察 codebase 后发现的 3 处底层逻辑偏差，必须在 1.x 之前修复，否则后续 task 无法落地。

- [x] 0.1 **Flyway V7 迁移**：`product` 表新增 `stock INTEGER NOT NULL DEFAULT 0` 列（DDL 原表无库存字段），加 `CHECK (stock >= 0)` 与 `idx_product_merchant_status_stock` 索引以备调试查询
- [x] 0.2 `Product` JPA 实体新增 `Integer stock` 字段（含 getter/setter，默认值 0）
- [x] 0.3 **修正 spec 中 `SessionScopeService` 引用**：实际类是 `SessionScopeCache`（位于 `voice-shopping-business/.../scope`），API 为 `Optional<SessionScope> get(String sessionId)`，cache miss 时业务侧 fall back `SessionScope.platformWide(userId)`；本次 OrderService.preview 注入 `SessionScopeCache` 而非虚构的 Service
- [x] 0.4 **品牌字段读取**：`Product` 无 `brand` 列，品牌存于 `attributes JSONB`（`{"brand":"Asics"}`）。OrderService.confirm 发布 UserPurchasedEvent 时 brand 取自 `(String) product.getAttributes().get("brand")`，category 取自 `product.getCategoryL1()`

## 1. 基础设施（DTO / 常量 / Repository）

- [x] 1.1 在 `voice-shopping-common` 模块 `com.voiceshopping.common.dto.order` 包新增 `PendingOrder` record（字段：sessionId, userId, merchantId, productId, productName, skuCode, quantity, unitPrice, totalAmount）
- [x] 1.2 在 `RedisKeys` 常量类追加 `PENDING_ORDER_PREFIX = "vs:pending_order:"` 与拼接方法 `pendingOrderKey(String sessionId)`
- [x] 1.3 在 `voice-shopping-business` 模块 `UserPurchasedEvent` 增加可空 `String sessionId` 字段（含 getter/构造器重载），保证既有调用方仍可用
- [x] 1.4 在 `ProductRepository` 新增 `@Modifying @Query(nativeQuery=true)` 方法 `int decrementStock(Long productId, int qty)`，SQL 见 design.md D2
- [x] 1.5 在 `application.yml` 增加配置项 `voice-shopping.order.enabled`（默认 true）、`voice-shopping.order.pending-ttl-seconds`（默认 600），并校验 `application-test.yml` 已覆盖

## 2. PendingOrderStore（业务层 Redis 暂存）

- [x] 2.1 在 `voice-shopping-business` 新增包 `com.voiceshopping.business.order`（若不存在）
- [x] 2.2 创建 `PendingOrderStore` 组件，构造器注入 `StringRedisTemplate` 与共享 `ObjectMapper`，从 `@Value("${voice-shopping.order.pending-ttl-seconds:600}")` 读 TTL
- [x] 2.3 实现 `put(PendingOrder po)`：JSON 序列化后 `opsForValue().set(key, json, Duration.ofSeconds(ttl))`
- [x] 2.4 实现 `get(String sessionId): PendingOrder`：miss 返回 null（不抛异常），反序列化失败 WARN 日志后视为 miss
- [x] 2.5 实现 `remove(String sessionId)`：幂等
- [x] 2.6 单元测试 `PendingOrderStoreTest`：put/get 往返、TTL 边界、get miss、remove 幂等、反序列化失败容错

## 3. OrderReferenceResolver（业务层引用解析）

- [x] 3.1 创建 `OrderReferenceResolver` 组件
- [x] 3.2 定义常量：`ORDINAL` 正则（`第?([一二三四五六七八九十1-9])(?:款|个|种|号|件|双)?`）、`REVERSE` 正则（`倒数|最后第`）
- [x] 3.3 实现 `resolve(LastRecommendationsSnapshot snapshot, String utterance): Optional<Long>` 按 design.md D6 优先级：REVERSE → 位置语义 → ORDINAL → empty
- [x] 3.4 实现 `ordinalToIndex(String)` 中文/数字 → 0-based index 转换（含"十"→9）
- [x] 3.5 单元测试 `OrderReferenceResolverTest`：覆盖 spec 中 7 个 Scenario + 边界（"第二天到" 不匹配、"中间" 偶数/奇数 size）

## 4. OrderService 扩展（在现有 query-only 之上叠加 write 能力）

- [x] 4.1 在 `OrderService` 构造器追加依赖：`ProductRepository productRepository`, `PendingOrderStore pendingOrderStore`, `SessionScopeService sessionScopeService`, `ApplicationEventPublisher applicationEventPublisher`（同时保留既有 `OrderRecordRepository`）
- [x] 4.2 实现 `preview(String sessionId, Long userId, Long productId, int quantity): PendingOrder`：查商品 → 校验 scope → 校验库存 → 构造 PendingOrder → 写 Redis → 返回；对应 design D5 + spec "OrderService.preview 生成预览单"
- [x] 4.3 实现 `@Transactional confirm(String sessionId): OrderRecord`：取 pending → 原子扣库存 → 二次校验 merchantId → 构造 OrderRecord → save → 删 Redis → 发 UserPurchasedEvent（在事务内发布，含 sessionId）；对应 design D2/D4/D8 + spec "OrderService.confirm 真实落单"
- [x] 4.4 实现 `cancel(String sessionId)`：直接 `pendingOrderStore.remove(sessionId)`
- [x] 4.5 在 javadoc 区分"查询方法"（带主体强制过滤）与"写入方法"（preview/confirm/cancel），引用 CLAUDE.md 主体强制过滤原则
- [x] 4.6 单元测试 `OrderServiceWriteTest`：覆盖 spec preview / confirm / cancel 全 Scenario，包括跨商家越权、库存被抢、商品归属异常、事件发布断言
- [x] 4.7 并发回归测试：MockMvc 或 Spring `@SpringBootTest` 启动 2 线程同时 confirm 同一 productId，断言其中一个落单成功、另一个抛"库存不足"，最终 stock=0 — **后置**：项目尚未引入 Testcontainers，需先把 Postgres 集成测试基建做起来，留给下个 change（"infra-testcontainers"）；当前 PG 原子 UPDATE 的正确性已被 SQL 语义保证，单元层面无法可靠模拟行级锁。

## 5. 监听器事务化改造

- [x] 5.1 `UserBehaviorSink.onPurchased` 把 `@EventListener` 改为 `@TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT)`，保留 `@Async`；`onViewed` 保持原状
- [x] 5.2 在 `LongTermMemoryWriter` 新增 `onPurchasedFlushLongTerm(UserPurchasedEvent event)` 方法：`@Async @TransactionalEventListener(AFTER_COMMIT)`；sessionId==null 时静默返回；非空时调用 `flushOnSessionEnd(sessionId, userId)`
- [x] 5.3 单元测试 `UserBehaviorSinkTransactionalTest`：手动构造 `TransactionTemplate`，验证 rollback 不触发、commit 触发
- [x] 5.4 单元测试 `LongTermMemoryWriterListenerTest`：sessionId=null 静默、sessionId 非空触发 flush
- [x] 5.5 集成回归：跑现有 `UserBehaviorSinkTest`，确认事件由 `TestApplicationEventPublisher` 在事务内发布时监听器仍能触发

## 6. Orchestrator 接入

- [x] 6.1 在 `OrchestratorService` 构造器追加注入：`OrderService`, `PendingOrderStore`, `OrderReferenceResolver`，并读取 `@Value("${voice-shopping.order.enabled:true}") boolean orderEnabled`
- [x] 6.2 抽出常量：`YES_PHRASES` / `NO_PHRASES` List 常量，实现 `containsNo(String)` 与 `containsYes(String)`（YES 内部用 containsNo 短路）；对应 spec "containsYes / containsNo 优先级规则"
- [x] 6.3 实现 `handleOrderConfirm(String sessionId, Long userId, SessionState state, String utterance): BranchOutcome`：按 design D9/D10 + spec "Orchestrator handleOrderConfirm 分支" 流程
- [x] 6.4 改造 `handle(...)` 入口：加载 state 后判断 `phase==ORDER_CONFIRM && orderEnabled` → 走 `handleOrderConfirm`；非 null outcome 走 compliance / 记忆 / 状态写回（intent=ORDER_CONFIRM）；null 时 phase 回退 RECOMMEND 并 fall through 到 IntentService 链路；对应 spec "handle 入口 phase=ORDER_CONFIRM 短路"
- [x] 6.5 修改 `dispatch` 中 `ORDER_CONFIRM` 分支：`orderEnabled=false` 时保留旧 `ORDER_CONFIRM_REPLY` 占位话术；`orderEnabled=true` 时复用 `handleOrderConfirm`（首次进入：state.phase 此时尚未是 ORDER_CONFIRM，pending 必为 null，走"resolve → preview"路径）
- [x] 6.6 单元测试 `OrchestratorOrderConfirmTest`：6 个 Scenario（短路+YES成功、短路+NO取消、短路+模糊反问、短路+库存被抢友好回退、首次 ORDER_CONFIRM 触发 preview、pending 过期回退后正常分类）
- [x] 6.7 验证 `voice.shopping.orchestrator.handle` Timer 在短路路径仍以 `intent=ORDER_CONFIRM` tag 记录

## 7. 端到端验证与回归

- [x] 7.1 `mvn -pl voice-shopping-business test`：本次新增的 4 个测试类全部通过 — 152 tests, 0 failures, 0 errors
- [x] 7.2 `mvn -pl voice-shopping-infrastructure test`：`ProductRepository.decrementStock` 集成测试（Testcontainers PG）通过 — **后置**：infra 模块当前无 Testcontainers，与 4.7 一并留给下个 change
- [x] 7.3 `mvn test`：项目全量测试通过，无既有用例回归 — business 152 + web 9 = 161 tests, 0 failures
- [x] 7.4 `ChatDebugController` 手动验证：跑完整 "想买跑鞋 → 推荐 → 第二款 → 确认 → 成功" 闭环；观察 `order_record` 表写入、`product.stock` 减 1、`user_profile_dynamic.purchase_count` +1 — **手动**：需用户起 PG/Redis/DashScope 实跑
- [x] 7.5 `ChatDebugController` 手动验证过期回退：preview 后等 11min（或临时把 TTL 设为 5s）→ 说"再看看高端的"→ 应进入 PRODUCT_COMPARE 而非"是哪一款？" — **手动**
- [x] 7.6 `ChatDebugController` 手动验证跨商家边界：构造 SessionScope=[merchant=1] 但 productId 属于 merchant=2 → preview 应抛 403 ForbiddenException — **手动**
- [x] 7.7 确认 `LongTermMemoryWriter.flushOnSessionEnd` 被异步触发，`user_profile_dynamic.category_prefs` 含 +0.05、`brand_prefs` 含 +0.03 — **手动**
- [x] 7.8 走查 `git diff` 确认：无硬编码 Redis key 字面量、无硬编码 TTL、无 `Map<String, Object>` 出现在接口参数、所有新方法异常都 fail fast 抛出而非吞掉

## 8. 文档与提交

- [x] 8.1 更新 `CLAUDE.md` "已实现功能" 列表：追加"✅ 订单下单闭环（preview/confirm/cancel + PG 原子扣库存 + AFTER_COMMIT 画像/长期记忆回流）"
- [x] 8.2 更新 `CLAUDE.md` "Redis Key 规范"表，新增 `vs:pending_order:{sessionId}` 一行（TTL 10min，用途"订单确认前的预览态暂存"）
- [x] 8.3 `git commit -m "feat(order-placement): 实现下单确认闭环 + PG 原子扣库存 + AFTER_COMMIT 事件回流"`
- [x] 8.4 运行 `/opsx:archive order-placement-flow` 把本次 change 的 specs 合并到 `openspec/specs/`，归档 change
