## 1. Infrastructure 层 — Entity 与 Repository

- [x] 1.1 在 `voice-shopping-infrastructure` 创建 `com.voiceshopping.infrastructure.repository.entity.OrderRecord`，映射 `order_record` 全部 16 个业务字段，`@Table(name = "order_record")`，`@Id` + `@GeneratedValue(IDENTITY)`
- [x] 1.2 在 `OrderRecord.java` 内（或邻近文件）定义 `OrderItemJson` Record/类，包含 `productId / name / price / quantity` 四字段
- [x] 1.3 `items` 字段使用 `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"`，类型为 `List<OrderItemJson>`，默认值 `new ArrayList<>()`
- [x] 1.4 `aiContext` 字段使用 `@JdbcTypeCode(SqlTypes.JSON)` + `columnDefinition = "jsonb"`，类型为 `Map<String, Object>`，默认 `new HashMap<>()`
- [x] 1.5 `status` 字段保持 `String` 类型（不引入 enum），默认 "CREATED"
- [x] 1.6 在 `voice-shopping-infrastructure` 创建 `com.voiceshopping.infrastructure.repository.OrderRecordRepository`，继承 `JpaRepository<OrderRecord, Long>`，类级 Javadoc 写明「业务 Service 禁止直接调用 findById/findAll，所有订单查询必须走带主体的方法」
- [x] 1.7 在 `OrderRecordRepository` 声明四个派生查询方法：`findByIdAndUserId` / `findByIdAndMerchantId` / `findAllByUserIdOrderByCreatedAtDesc(..., Pageable)` / `findAllByMerchantIdOrderByCreatedAtDesc(..., Pageable)`

## 2. Business 层 — OrderService

- [x] 2.1 在 `voice-shopping-business` 创建包 `com.voiceshopping.business.order` 与 `OrderService` `@Service` 类，构造器注入 `OrderRecordRepository`；**不引入 Sa-Token 依赖**（决策 D8）
- [x] 2.2 实现 `OrderDTO getForUser(Long userId, Long orderId)`：调 `findByIdAndUserId(orderId, userId)`；命中映射 DTO 返回，未命中 → 调 `existsById(orderId)`，WARN 日志 `orderId / userId / exists` 后抛 `NotFoundException("订单不存在")`
- [x] 2.3 实现 `Page<OrderDTO> listMine(Long userId, Pageable pageable)`：调 `findAllByUserIdOrderByCreatedAtDesc(userId, pageable)`，`Page.map(OrderDTO::from)` 转换
- [x] 2.4 实现 `Page<OrderDTO> listForMerchant(Long merchantId, Pageable pageable)`：直接调仓储方法，**不做角色校验**；方法 Javadoc 标注「内部 Service 方法，不暴露给 Controller，调用方需自行确认 merchantId 合法性」
- [x] 2.5 类级 Javadoc 引用 CLAUDE.md「主体强制过滤原则」章节

## 3. Web 层 — DTO 与 Controller

- [x] 3.1 在 `voice-shopping-common` 创建 `com.voiceshopping.common.dto.order.OrderDTO` Record，包含 16 个业务字段（与既有 DTO 集中在 common 模块的惯例一致）
- [x] 3.2 在同一文件或邻近创建 `OrderItemDTO` Record（`productId / name / price / quantity`）
- [x] 3.3 在 `OrderService` 中提供私有方法 `toDto(OrderRecord)` 做 entity → DTO 映射（DTO 在 common 模块，不能反向依赖 infrastructure，因此映射放 Service 层），`items` 用 `Optional.ofNullable(...).orElse(emptyList())` 兜底，`aiContext` 同理；`receiverPhone / receiverAddr / receiverName` 明文透传，**不做脱敏**
- [x] 3.4 创建 `com.voiceshopping.web.controller.OrderController`，`@RestController` + `@RequestMapping("/api/v1/orders")`，构造器注入 `OrderService`
- [x] 3.5 实现 `GET /api/v1/orders/mine`：方法签名接 `Pageable pageable`，Controller 内通过 `StpUtil.getLoginIdAsLong()` 取 userId，调 `orderService.listMine(userId, pageable)` 包 `ApiResult.ok`
- [x] 3.6 实现 `GET /api/v1/orders/{orderId}`：路径参 `Long orderId`，Controller 内通过 `StpUtil.getLoginIdAsLong()` 取 userId，调 `orderService.getForUser(userId, orderId)` 包 `ApiResult.ok`
- [x] 3.7 不暴露任何 `listForMerchant` 对应的 HTTP 接口；类级 Javadoc 写明 `listForMerchant` 故意未提供对外路径

## 4. 文档 — CLAUDE.md 主体强制过滤原则

- [x] 4.1 在 `voice-shopping/CLAUDE.md` 「编码规范」章节之后、「Redis Key 规范」章节之前，新增 `## 主体强制过滤原则` 章节
- [x] 4.2 章节内容包含：范围（先针对 order_record，后续 refund_record 等复用）、禁止项（`findById` / `findAll` 直接调用）、推荐写法（带主体的派生查询命名约定）、理由（编译期拦截 + 不泄露存在性）、边界（商品/FAQ 走 SessionScope，不在范围）
- [x] 4.3 在「已实现功能」清单追加 `✅ 订单查询主体强制过滤（OrderRecord/OrderRecordRepository/OrderService/OrderController + 仅暴露 /api/v1/orders/mine 与 /api/v1/orders/{orderId}）`

## 5. 验证 — 启动与冒烟

- [x] 5.1 `mvn -pl voice-shopping-web -am compile` 编译通过，无未引用 import / 未定义符号
- [x] 5.2 启动应用，Hibernate 启动日志中能看到 `OrderRecord` entity 被识别（不会因 JSONB 映射类型失败）
- [x] 5.3 手动构造一条 `order_record` 记录（直接 INSERT 或复用现有种子数据），用 phone 登录拿到 token，`GET /api/v1/orders/mine` 返回 200 且 `content[0].userId` 等于当前登录 userId
- [x] 5.4 用同一 token 请求 `GET /api/v1/orders/{该订单 id}` 返回 200；用同一 token 请求 `GET /api/v1/orders/999999`（不存在）返回 404 + `code != 0` + msg = "订单不存在"，应用日志中能看到 WARN `exists=false`
- [x] 5.5 用同一 token 请求 `GET /api/v1/orders/{属于另一用户的订单 id}` 返回 404 + msg = "订单不存在"（不是 403），应用日志中 WARN `exists=true`
- [x] 5.6 不带 token 请求 `GET /api/v1/orders/mine` 被 Sa-Token 拦截，返回 401（具体响应格式沿用现有兜底）

## 6. 验收

- [x] 6.1 运行 `mvn -pl voice-shopping-business,voice-shopping-web,voice-shopping-infrastructure test` 全绿
- [x] 6.2 `openspec validate order-subject-scoped-query --strict` 通过
- [x] 6.3 Code Review 检查清单：`OrderService` 中无 `findById` / `findAll` 直接调用；Controller 中无手工构建错误响应；DTO 中无 phone 脱敏代码
