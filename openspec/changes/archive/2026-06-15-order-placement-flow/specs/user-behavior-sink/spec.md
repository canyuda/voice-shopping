## MODIFIED Requirements

### Requirement: UserBehaviorSink event listener
The system SHALL provide a `UserBehaviorSink` class in the business module that listens for Spring ApplicationEvent events and updates the `user_profile_dynamic` table in real time.

**`onPurchased` 监听器的事务语义** SHALL 从直接 `@EventListener` 升级为 `@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` + `@Async`，确保：

- 订单事务回滚时 `onPurchased` MUST NOT 被调用（避免画像被未落库的下单事件污染）
- 订单事务提交后 `onPurchased` 在另一线程异步执行（避免阻塞 `OrderService.confirm` 的响应链路）
- `onViewed` 监听器 SHALL 保持 `@EventListener` + `@Async`（浏览事件本身不在 PG 事务内发布，无需事务监听）

#### Scenario: onViewed event updates preferences
- **WHEN** an event indicating user viewed a product (with userId, category, brand) is received
- **THEN** the `category_prefs` for that category is incremented by the configured view weight (default 0.05), `brand_prefs` for that brand is incremented by the view weight, and a behavior entry is appended to `recent_behavior`

#### Scenario: onPurchased event updates preferences
- **WHEN** an event indicating user purchased a product (with userId, category, brand, amount) is received **and 触发事件的事务已提交**
- **THEN** the `category_prefs` for that category is incremented by the configured purchase weight (default 0.15), `brand_prefs` for that brand is incremented by the purchase weight, `purchase_count` is incremented by 1, `avg_order_amount` is recalculated, `last_purchase_at` is updated, and a behavior entry is appended to `recent_behavior`

#### Scenario: onPurchased 不在事务回滚时触发
- **WHEN** OrderService.confirm 在 publishEvent 之后但 commit 之前抛异常导致事务回滚
- **THEN** `onPurchased` 未被调用，user_profile_dynamic 无任何写入

#### Scenario: onPurchased 异步执行
- **WHEN** OrderService.confirm 事务提交完成
- **THEN** `onPurchased` 在与发布线程不同的线程上执行，confirm 调用方不感知其耗时

#### Scenario: New category or brand gets initialized
- **WHEN** a view or purchase event references a category or brand not yet in the preferences map
- **THEN** the new key is added with the increment value as its initial score

#### Scenario: 旧路径 sessionId 为 null 时画像照常更新
- **WHEN** UserPurchasedEvent 被 sessionId=null 触发（后台手工补录场景）
- **THEN** onPurchased 正常完成画像写入，不读取 sessionId 字段，不抛 NPE
