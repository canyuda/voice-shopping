## Why

`order_record` 表已在 V1 迁移建好，但 Java 侧还没有 Entity / Repository / Service / Controller。订单是用户私有数据，传统"先 `findById` 再判 `userId`"的写法一旦遗漏 ownership 校验就直接越权；同时 403/404 的差异会泄露资源存在性。本次借首次落地订单查询的机会，确立**主体强制过滤原则**——Repository 派生查询方法名显式带 `AndUserId` / `AndMerchantId`，把"忘传身份"的越权写法在编译期拦下。

## What Changes

- 新增 `OrderRecord` JPA Entity，映射 `order_record` 表全部业务字段（含 `items` / `aiContext` JSONB）
- 新增 `OrderRecordRepository`，继承 `JpaRepository<OrderRecord, Long>`，仅暴露带主体维度的派生查询：
  - `findByIdAndUserId(Long id, Long userId)`
  - `findByIdAndMerchantId(Long id, Long merchantId)`
  - `findAllByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable)`
  - `findAllByMerchantIdOrderByCreatedAtDesc(Long merchantId, Pageable pageable)`
- 新增 `OrderService` 三个方法：
  - `getForUser(Long orderId)` — 查当前登录用户的某一笔订单详情
  - `listMine(Pageable pageable)` — 分页查当前登录用户全部订单
  - `listForMerchant(Long merchantId, Pageable pageable)` — 商家维度分页查询，**当前为内部 Service 方法**，调用方可信，不做角色校验
- 新增 `OrderController`：
  - `GET /api/v1/orders/mine?page=&size=` — 从 Sa-Token 取 userId，分页返回 `Page<OrderDTO>`
  - `GET /api/v1/orders/{orderId}` — 返回单笔 `OrderDTO`，找不到或不属于当前登录用户一律 404；**仅日志中通过额外 `existsById` 调用区分两种情况**便于排查，对外文案统一
- 新增 `OrderDTO`（Java Record），暴露 `order_record` 全部业务字段，**不做 phone / 地址脱敏**，items 反序列化为 `List<OrderItem>` 子 Record
- `CLAUDE.md` 新增「主体强制过滤原则」章节，先针对 Order 写明白，放在「编码规范」之后、「Redis Key 规范」之前

不在本次范围：
- 订单创建 / 状态流转 / 支付回调
- 商家后台角色（`MERCHANT_ADMIN` 等）和 `/api/v1/orders/merchant/...` 对外接口
- ArchUnit / 自定义 `@NoRepositoryBean` 的硬约束（Q1 选择软约束方案）
- AppUser / SessionScope / 商品检索路径（已存在，复用）

## Capabilities

### New Capabilities
- `order-query`: 订单查询能力（用户视角 + 商家视角），强制带主体维度过滤，杜绝越权和资源存在性泄露

### Modified Capabilities
- 无（既有 spec 不变；`merchant-data-isolation` 关注的是商品/FAQ 检索路径的 SessionScope 强制过滤，与本次订单的"主体强制过滤"是同源原则的不同落点，不修改其 requirements）

## Impact

- **代码**：
  - `voice-shopping-infrastructure`：新增 `OrderRecord` entity、`OrderRecordRepository`
  - `voice-shopping-business`：新增 `OrderService`
  - `voice-shopping-web`：新增 `OrderController`、`OrderDTO`（含 `OrderItem` 子 Record）
- **数据库**：无 schema 变更，复用 V1 已建表
- **API**：
  - 新增 `GET /api/v1/orders/mine`、`GET /api/v1/orders/{orderId}`
  - 鉴权依赖现有 Sa-Token；未登录由 `NotLoginException` 全局兜底
- **依赖**：无新增 Maven 依赖；Spring Data JPA 自带的派生查询语法即可
- **文档**：`CLAUDE.md` 新增章节，作为后续实体（refund_record 等）的复用模板
