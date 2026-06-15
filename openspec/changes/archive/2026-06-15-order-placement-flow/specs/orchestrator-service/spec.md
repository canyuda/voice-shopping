## MODIFIED Requirements

### Requirement: ORDER_CONFIRM 分支占位

意图为 ORDER_CONFIRM 时（**或**当前 `session_state.phase == "ORDER_CONFIRM"` 触发 phase 短路时），系统 SHALL 进入订单确认子状态机：

1. 当配置 `voice-shopping.order.enabled = false` 时，系统 SHALL 直接返回 `new EmotionResult("好，给你下单（完整下单逻辑后续补）", List.of())`，按规范写入短期记忆与 session_state（保留旧占位行为作 rollback fallback）
2. 配置启用时（默认），系统 SHALL 调用 `handleOrderConfirm(sessionId, userId, state, utterance)` 完成：
   - 引用项解析（`OrderReferenceResolver`）
   - 预览单生成（`OrderService.preview` + `PendingOrderStore`）
   - YES/NO 二次确认（`containsYes` / `containsNo`，NO 优先）
   - 落单（`OrderService.confirm` + PG 原子扣库存）
   - 取消（`OrderService.cancel`）
3. `handleOrderConfirm` 内部所有分支 MUST 返回 `BranchOutcome`，包括确认成功（phase=`ENDED`）、取消（phase=`RECOMMEND`）、库存被抢的友好回退（phase=`RECOMMEND`）、追问"哪一款？"（phase 保持 `ORDER_CONFIRM`）

完整子状态机行为详见 `order-placement-flow` capability spec。

#### Scenario: 开关启用 + pending 存在 + YES 成功落单
- **WHEN** `voice-shopping.order.enabled=true`，session 已有 pending，utterance="确认下单"
- **THEN** EmotionResult.speechText 以 "下单成功，订单尾号 " 开头，session_state.phase=ENDED

#### Scenario: 开关启用 + 首次 ORDER_CONFIRM 触发预览
- **WHEN** `voice-shopping.order.enabled=true`，意图首次为 ORDER_CONFIRM 且 state.lastRecommendations 含 3 件商品，utterance="第二款"
- **THEN** OrderService.preview 被调用，Redis pending 已写入，session_state.phase=ORDER_CONFIRM，speechText 含"确认下单吗"

#### Scenario: 开关关闭回到占位话术
- **WHEN** `voice-shopping.order.enabled=false`，意图为 ORDER_CONFIRM
- **THEN** EmotionResult.speechText = "好，给你下单（完整下单逻辑后续补）"，OrderService.preview/confirm 均未被调用

#### Scenario: 短期记忆与 session_state 仍按规范写入
- **WHEN** 订单分支以任意路径返回
- **THEN** ShortTermMemory 中按规范追加 ASSISTANT 和 TURN turn，session_state 按 BranchOutcome 写回

---

## ADDED Requirements

### Requirement: handle 入口 phase=ORDER_CONFIRM 短路

系统 SHALL 在 `handle` 入口加载 `session_state` 后立即检查 phase：

- 若 `state.phase == "ORDER_CONFIRM"` 且 `voice-shopping.order.enabled=true`：
  1. 调用 `handleOrderConfirm(sessionId, userId, state, utterance)`，**跳过 IntentService 与 reviseIntent**
  2. 若返回非 null `BranchOutcome` → 走 compliance / 记忆 / 状态写回正常流程，意图标签写 `ORDER_CONFIRM`
  3. 若返回 null（pending 已过期且 resolver 失败）→ 将 `state.phase` 改为 `RECOMMEND`，**继续 fall through** 到 IntentService 正常意图链路
- 若 `state.phase != "ORDER_CONFIRM"` 或开关关闭 → 走原有 IntentService → reviseIntent → dispatch 链路

#### Scenario: 短路跳过 IntentService
- **WHEN** state.phase=ORDER_CONFIRM 且 pending 存在 且 utterance="确认"
- **THEN** IntentService.classify 未被调用一次

#### Scenario: pending 过期 + 无法 resolve → 回退后正常分类
- **WHEN** state.phase=ORDER_CONFIRM，Redis pending 已过期，utterance="再给我推荐点贵的"
- **THEN** state.phase 被改为 RECOMMEND，IntentService.classify 被正常调用，最终意图按 IntentService 输出（可能进入 PRODUCT_COMPARE 等）

#### Scenario: 开关关闭时不短路
- **WHEN** `voice-shopping.order.enabled=false`，state.phase=ORDER_CONFIRM
- **THEN** IntentService.classify 被正常调用，handleOrderConfirm 未被调用

#### Scenario: 短路路径 timer tag 仍为 ORDER_CONFIRM
- **WHEN** 走短路且 handleOrderConfirm 返回正常 BranchOutcome
- **THEN** `voice.shopping.orchestrator.handle` Timer 以 `intent=ORDER_CONFIRM` 记录耗时

---

### Requirement: OrchestratorService 新增订单依赖注入

系统 SHALL 在 `OrchestratorService` 构造器追加注入：
- `OrderService` —— 现有，新增 preview/confirm/cancel 方法
- `PendingOrderStore` —— 新增
- `OrderReferenceResolver` —— 新增

依赖 final 字段、构造器注入，与现有依赖注入风格一致。

#### Scenario: 三个依赖完整注入
- **WHEN** Spring 容器启动
- **THEN** OrchestratorService bean 含 orderService / pendingOrderStore / referenceResolver 三个非 null 字段
