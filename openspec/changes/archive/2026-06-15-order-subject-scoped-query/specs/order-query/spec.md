## ADDED Requirements

### Requirement: OrderRecord Entity

系统 SHALL 在 `voice-shopping-infrastructure` 模块下提供 `OrderRecord` JPA Entity，类位置为 `com.voiceshopping.infrastructure.repository.entity.OrderRecord`，`@Table(name = "order_record")`。

实体 SHALL 映射 `order_record` 表全部业务字段：`id` (BIGSERIAL PK) / `merchantId` / `userId` / `sessionId` (String，UUID 字符串) / `orderNo` / `items` (JSONB) / `totalAmount` (BigDecimal) / `status` (String) / `agentAttribution` (Boolean) / `sourceIntent` / `aiContext` (JSONB) / `receiverName` / `receiverPhone` / `receiverAddr` / `createdAt` / `updatedAt`。

`items` 字段 SHALL 使用 `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"` 映射为 `List<OrderItemJson>`，其中 `OrderItemJson` 是定义在同一文件或邻近的内嵌 record/类，含 `productId` (Long) / `name` (String) / `price` (BigDecimal) / `quantity` (Integer) 四个字段，字段命名沿用 V1 注释中的 `productId/name/price/quantity`。

`aiContext` 字段 SHALL 使用 `@JdbcTypeCode(SqlTypes.JSON)` 映射为 `Map<String, Object>`（与 `Product.attributes` 同样的处理方式）。

`status` 字段在 Java 层 SHALL 保持为 `String`（不引入 enum），以避免与 DB CHECK 约束的字符串值不一致带来的额外维护成本。

#### Scenario: Entity 字段映射 order_record 表
- **WHEN** 检查 `OrderRecord.java` 字段定义
- **THEN** 包含上述全部 16 个业务字段，且 `@Table(name = "order_record")`、`@Id` 配 `@GeneratedValue(strategy = GenerationType.IDENTITY)`

#### Scenario: items 反序列化为强类型列表
- **WHEN** 从数据库加载一条 `items = '[{"productId":8821,"name":"Asics","price":479,"quantity":1}]'` 的订单
- **THEN** `orderRecord.getItems()` 返回 `List<OrderItemJson>`，size = 1，元素 `productId == 8821L && quantity == 1`

### Requirement: OrderRecordRepository 派生查询

系统 SHALL 在 `voice-shopping-infrastructure` 模块下提供 `OrderRecordRepository`，继承 `JpaRepository<OrderRecord, Long>`，并 SHALL 显式提供以下带主体维度的派生查询方法：

- `Optional<OrderRecord> findByIdAndUserId(Long id, Long userId)`
- `Optional<OrderRecord> findByIdAndMerchantId(Long id, Long merchantId)`
- `Page<OrderRecord> findAllByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable)`
- `Page<OrderRecord> findAllByMerchantIdOrderByCreatedAtDesc(Long merchantId, Pageable pageable)`

仓储接口本身仍继承 `JpaRepository`，因此 `findById` / `findAll` 在编译期可见；本规范不通过类型系统硬拦，而是通过 `CLAUDE.md`「主体强制过滤原则」章节约束业务层调用方式。

仓储 Javadoc SHALL 在类级别注释中标注「业务 Service 禁止直接调用 `findById` / `findAll`，所有订单查询必须走带主体的方法」。

#### Scenario: 派生查询自动叠加 user_id 过滤
- **WHEN** 调用 `repo.findByIdAndUserId(100L, 7L)`
- **THEN** Spring Data JPA 生成 SQL `... WHERE id = ? AND user_id = ?`，参数依次为 100、7

#### Scenario: 分页按 created_at DESC
- **WHEN** 调用 `repo.findAllByUserIdOrderByCreatedAtDesc(7L, PageRequest.of(0, 20))`
- **THEN** 返回的 `Page<OrderRecord>` 按 `created_at` 降序排序，第一页最多 20 条

### Requirement: OrderService 主体强制过滤

系统 SHALL 在 `voice-shopping-business` 模块下提供 `OrderService` Spring `@Service`，类位置为 `com.voiceshopping.business.order.OrderService`，并 SHALL 提供以下方法：

- `OrderDTO getForUser(Long userId, Long orderId)`：调用 `orderRecordRepository.findByIdAndUserId(orderId, userId)`，命中则映射为 `OrderDTO` 返回；未命中 SHALL 抛 `NotFoundException("订单不存在")`。
- `Page<OrderDTO> listMine(Long userId, Pageable pageable)`：调用 `orderRecordRepository.findAllByUserIdOrderByCreatedAtDesc(userId, pageable)`，将 `Page<OrderRecord>` 通过 `Page.map(...)` 映射为 `Page<OrderDTO>` 返回。
- `Page<OrderDTO> listForMerchant(Long merchantId, Pageable pageable)`：直接调用 `orderRecordRepository.findAllByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)`，**不做角色校验**（当前阶段调用方为可信内部 Service / 后台任务）。

`OrderService` MUST NOT 依赖 Sa-Token / `StpUtil`；`userId` 与 `merchantId` 一律由调用方（Controller / 定时任务 / 测试）显式传入，与既有 `SessionService` 等惯例一致（详见 design.md 决策 D8）。

`getForUser` 在未命中分支 SHALL 在抛异常前以 WARN 级别记录日志，日志 MUST 通过 `orderRecordRepository.existsById(orderId)` 额外查一次以区分「不存在」和「他人订单」两种情况，便于排查越权探测；对外文案 SHALL 统一为「订单不存在」，不暴露差异。

`listForMerchant` 的 Javadoc SHALL 显式标注「内部 Service 方法，不暴露给 Controller，调用方需自行确认 merchantId 合法性」。

#### Scenario: 用户查自己的订单成功
- **WHEN** 订单 100 的 `user_id = 7`，调用 `orderService.getForUser(7L, 100L)`
- **THEN** 返回该订单对应的 `OrderDTO`

#### Scenario: 用户查他人订单返回 404
- **WHEN** 订单 100 的 `user_id = 9`，调用 `orderService.getForUser(7L, 100L)`
- **THEN** 抛出 `NotFoundException`，message = "订单不存在"；日志中 WARN 记录 `orderId=100, userId=7, exists=true`

#### Scenario: 订单不存在返回 404
- **WHEN** 数据库中无 id = 999 的订单，调用 `orderService.getForUser(7L, 999L)`
- **THEN** 抛出 `NotFoundException`，message = "订单不存在"；日志中 WARN 记录 `orderId=999, userId=7, exists=false`

#### Scenario: listMine 分页返回当前用户订单
- **WHEN** 调用 `orderService.listMine(7L, PageRequest.of(0, 20))`
- **THEN** 返回的 `Page<OrderDTO>` 全部条目 `userId == 7`，按 `createdAt` 降序

#### Scenario: listForMerchant 不校验调用者身份
- **WHEN** 任意线程调用 `orderService.listForMerchant(5L, PageRequest.of(0, 20))`
- **THEN** 直接返回 merchantId = 5 的订单分页，不抛 `NotLoginException` 或 `ForbiddenException`

### Requirement: OrderController 用户视角接口

系统 SHALL 在 `voice-shopping-web` 模块下提供 `OrderController`，类位置为 `com.voiceshopping.web.controller.OrderController`，`@RequestMapping("/api/v1/orders")`。

接口 SHALL 至少包含：

- `GET /api/v1/orders/mine`：接收 Spring 自动绑定的 `Pageable`（默认 `page=0`、`size=20`、`sort=createdAt,desc` 由 Service 兜底），返回 `ApiResult<Page<OrderDTO>>`，Controller 内通过 `StpUtil.getLoginIdAsLong()` 取当前 userId，调用 `orderService.listMine(userId, pageable)`。
- `GET /api/v1/orders/{orderId}`：路径参数 `orderId` (Long)，返回 `ApiResult<OrderDTO>`，Controller 内通过 `StpUtil.getLoginIdAsLong()` 取当前 userId，调用 `orderService.getForUser(userId, orderId)`。

Controller MUST 通过 `StpUtil.getLoginIdAsLong()` 取当前登录 userId（Sa-Token 调用仅在 Controller 层发生，业务层不感知 Sa-Token，详见 design.md 决策 D8）；MUST NOT 自行从 Header 解析 token（Sa-Token 拦截器/过滤器已处理）；MUST NOT 自行构建错误响应（异常交给 `GlobalExceptionHandler`）。

Controller MUST NOT 暴露 `listForMerchant` 对应的对外 HTTP 接口（与 Service 层 Javadoc 一致）。

#### Scenario: GET /api/v1/orders/mine 默认分页
- **WHEN** 已登录用户 7 请求 `GET /api/v1/orders/mine` 不传任何 query 参数
- **THEN** Sa-Token 通过校验，Controller 通过 `StpUtil.getLoginIdAsLong()` 取得 userId=7，调用 `orderService.listMine(7L, pageable)`，响应 `ApiResult.ok(page)`，HTTP 200

#### Scenario: GET /api/v1/orders/{orderId} 命中
- **WHEN** 已登录用户 7 请求 `GET /api/v1/orders/100`，订单 100 的 `user_id = 7`
- **THEN** 响应 `ApiResult.ok(orderDto)`，HTTP 200

#### Scenario: GET /api/v1/orders/{orderId} 未命中或他人订单
- **WHEN** 已登录用户 7 请求 `GET /api/v1/orders/999`（不存在）或 `GET /api/v1/orders/100`（属于用户 9）
- **THEN** 抛 `NotFoundException`，由 `GlobalExceptionHandler` 转为 HTTP 404 + `ApiResult` 错误体，message = "订单不存在"

#### Scenario: 未登录访问 /orders/mine 被 Sa-Token 拦截
- **WHEN** 未登录请求 `GET /api/v1/orders/mine`
- **THEN** Sa-Token 抛 `NotLoginException`，由全局兜底转为 401（具体响应格式沿用现有兜底逻辑）

### Requirement: OrderDTO 字段暴露

系统 SHALL 提供 `OrderDTO` Java Record，位置为 `com.voiceshopping.common.dto.order.OrderDTO`（与项目既有 DTO 集中在 `voice-shopping-common` 的惯例一致——`AgentDisplay` / `SessionScope` 等都在 common 模块）。

字段 SHALL 包含 `order_record` 表的全部业务字段：`id` / `merchantId` / `userId` / `sessionId` / `orderNo` / `items` (`List<OrderItemDTO>`) / `totalAmount` / `status` / `agentAttribution` / `sourceIntent` / `aiContext` / `receiverName` / `receiverPhone` / `receiverAddr` / `createdAt` / `updatedAt`。

`OrderItemDTO` SHALL 是定义在同一文件或邻近的 Record，包含 `productId` / `name` / `price` / `quantity` 四个字段。

DTO MUST NOT 对 `receiverPhone` / `receiverAddr` / `receiverName` 做任何脱敏（用户查看自己的订单，明文返回符合直觉）。

由于 `OrderDTO` 在 `voice-shopping-common` 模块、不能反向依赖 `voice-shopping-infrastructure`，entity → DTO 的映射 SHALL 放在 `OrderService` 内（私有方法 `toDto(OrderRecord)`），通过 `Page.map(this::toDto)` 实现分页转换；`items` / `aiContext` 不为 null 时直接透传，为 null 时按 `Collections.emptyList()` / `Collections.emptyMap()` 兜底。`items` 中每个 `OrderItemJson` 同样通过 lambda 映射为 `OrderItemDTO`。

#### Scenario: 全字段映射
- **WHEN** 一条 `OrderRecord` 持久化对象在 `OrderService` 内通过 `toDto(entity)` 私有映射方法转换
- **THEN** DTO 全部 16 个字段值与 entity 一致；`receiverPhone` 为原始 `13800138000` 而非脱敏值 `138****8000`

#### Scenario: items 子结构透传
- **WHEN** entity.items = `[{productId:1,name:"A",price:9.9,quantity:2}]`
- **THEN** dto.items 为 `List<OrderItemDTO>`，size = 1，元素字段一致

### Requirement: CLAUDE.md 主体强制过滤原则章节

`CLAUDE.md` SHALL 在「编码规范」章节之后、「Redis Key 规范」章节之前新增「主体强制过滤原则」章节。

章节内容 SHALL 至少明确以下要点：

- 范围：当前先针对 `order_record` 落地，后续如有 `refund_record` / 用户消息历史等私有数据实体，复用同一原则。
- 禁止项：业务 Service 禁止直接调用 `orderRepo.findById(id)` / `orderRepo.findAll()`。
- 推荐写法：`findByIdAndUserId` / `findByIdAndMerchantId` / `findAllByUserIdOrderBy...` / `findAllByMerchantIdOrderBy...` 等显式带主体维度的派生查询。
- 理由：让"忘传身份"在编译期就被拦下，而不是靠"先 `findById` 再判 ownership"的事后 if 校验；后者一旦遗漏直接越权，且 403/404 的差异会泄露资源存在性。
- 边界说明：商品 / FAQ 等"商家公开内容"走 `SessionScope` 过滤路径（见 `merchant-data-isolation` spec），不在此原则范围。

#### Scenario: CLAUDE.md 包含强制过滤章节
- **WHEN** 阅读 `voice-shopping/CLAUDE.md`
- **THEN** 在「编码规范」之后、「Redis Key 规范」之前能找到一个完整的「主体强制过滤原则」章节，包含上述五个要点
