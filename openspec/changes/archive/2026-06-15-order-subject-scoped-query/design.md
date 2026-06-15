## Context

`order_record` 表已在 V1 迁移建好（含 `(merchant_id, user_id)`、`(user_id, created_at DESC)` 等索引），但 Java 侧无任何对应代码。本次为首次落地订单查询；同时借此机会沉淀「主体强制过滤」的工程原则到 `CLAUDE.md`，影响后续所有用户/商家私有数据实体。

相关既有约束：
- `voice-shopping-business` 已有 `merchant-data-isolation` 能力，关注**商品/FAQ 检索**路径的 `SessionScope` 强制叠加 `merchant_id IN (...)`，与本次的「订单主体强制过滤」是**同源原则不同落点**，不能直接复用其代码路径。
- 已有 `ApiResult<T>` / `GlobalExceptionHandler` / `NotFoundException` / `ForbiddenException`；Sa-Token phone-only 登录已就绪。
- 数据库层 V1 已提供 `idx_order_record_user_created (user_id, created_at DESC)`、`idx_order_record_merchant_user (merchant_id, user_id)`、`idx_order_record_created_at (merchant_id, created_at DESC)` 三个索引，足以覆盖本次查询场景，无需新增 SQL。

## Goals / Non-Goals

**Goals:**
- 首次提供订单查询能力（用户视角），并强制带主体过滤
- 把「主体强制过滤」原则在 `CLAUDE.md` 显式化，作为后续私有数据实体（refund_record 等）的复用模板
- 让"忘传身份"的越权写法尽早暴露：通过 Repository 派生查询命名 + CLAUDE.md 约束 + Service/Controller 层不留 escape hatch

**Non-Goals:**
- 订单创建 / 状态流转 / 支付回调
- 商家后台对外接口与 `MERCHANT_ADMIN` 角色（`listForMerchant` 仅作内部 Service 方法保留）
- ArchUnit / `@NoRepositoryBean` 等硬约束（本次选择软约束方案，详见决策 D1）
- `receiverPhone` / `receiverAddr` 的脱敏（用户查自己订单，明文返回符合直觉）
- 订单维度的 Redis 缓存（首次落地保持简单，按需再加）

## Decisions

### D1. 软约束 vs 硬约束：选择软约束（CLAUDE.md + 派生查询命名）

**选择**：`OrderRecordRepository extends JpaRepository<OrderRecord, Long>`，仍保留 `findById` / `findAll`，靠 `CLAUDE.md`「主体强制过滤原则」章节 + Code Review 约束业务层调用。

**备选方案及为何不选**：
- **方案 B（自定义 `@NoRepositoryBean SubjectScopedRepository`）**：把 `findById` / `findAll` 从类型系统层面抹掉。优势是编译期硬拦；代价是失去 `JpaRepository` 全部便利方法（`saveAll` / `flush` / `existsById` / 复杂分页等），后续每加一个方法都要手写在父接口里。**当前阶段订单 CRUD 还在快速演进**（创建 / 状态流转尚未实现），过早收紧类型系统会显著拖慢推进。
- **方案 C（ArchUnit 单测）**：用静态分析在 CI 层拦 `OrderRecordRepository.findById/findAll` 出现在 business 层。优势是不削弱类型系统、又有自动化兜底；代价是引入 `archunit` Maven 依赖和测试基建，且当前 business 层尚无任何此类规约的先例。可作为未来增强项，**不在本次范围**。

**为何软约束在当下足够**：
- `getForUser` 内部需要调用 `existsById` 用于诊断日志（D5），方案 B 反而需要为这个调用专门在父接口暴露 `existsById`，进一步证明硬约束在当前阶段的负担。
- 首次落地的代码量小、Reviewer 注意力集中，CLAUDE.md 显式条款 + Repository 类级 Javadoc 警告足以拦住"自然遗漏"。

### D2. Service/Controller 命名不对称：`OrderRecord` (entity) + `OrderService` (业务)

实体层贴近持久化模型，命名为 `OrderRecord` / `OrderRecordRepository`；业务/接口层使用业务语义 `OrderService` / `OrderController` / `OrderDTO`。这种**持久化模型与业务模型分离**的小不对称在项目其他能力里已有先例（如 `Session` entity 对应 `SessionService`），保持一致。

### D3. 分页：使用 Spring Data 原生 `Pageable` + `Page<T>`

**选择**：`listMine(Pageable pageable)` 直接接 Spring 自动绑定的 `Pageable`，由 `PageableHandlerMethodArgumentResolver` 解析 `?page=&size=&sort=` query string。Service 层把 `Page<OrderRecord>` 通过 `Page.map(OrderDTO::from)` 转 `Page<OrderDTO>` 后返回。

**默认值**：在 Controller 方法签名上不显式设默认值，依赖 Spring Boot 全局默认（`page=0, size=20`）。`sort` 兜底由 Service 内部固定为 `created_at DESC`：调用 repository 派生方法 `findAllByUserIdOrderByCreatedAtDesc(...)` 已强制排序，**忽略客户端传入的 sort 参数**——避免客户端通过 `?sort=receiver_phone` 之类操作触发非索引列排序。

**备选**：自定义 `PageRequestDTO`、或返回 `List<OrderDTO>` + 上限。前者多此一举（Spring 原生支持已够用）；后者不便分页，扩展性差。

### D4. 响应包装：`ApiResult<Page<OrderDTO>>` 直接透传 `Page` 对象

**选择**：Controller 返回 `ApiResult.ok(page)`，让 Jackson 序列化 `Page` 默认包含 `content` / `totalElements` / `totalPages` / `number` / `size` 等字段。

**已知风险**：Spring 团队官方 deprecate 了 "serialize PageImpl directly" 的做法，未来可能在 Jackson 默认行为上变化。**当前选择优先简单**——若未来出现序列化兼容问题，再统一替换为 `PagedModel` 或自定义 `PageResultDTO`。

### D5. `getForUser` 的 404 语义：对外统一，对内日志区分

**选择**：未命中分支先 `existsById(orderId)` 一次（仅在未命中分支调用，命中分支不调），WARN 日志中记录 `orderId / userId / exists` 三元组；对外抛 `NotFoundException("订单不存在")`。

**理由**：
- 对外 403/404 差异会泄露 `orderId` 是否存在。统一 404 是默认安全姿态。
- 但运维侧需要区分"用户写错了 orderId" vs "用户在尝试越权探测"，前者是无害噪声，后者是安全信号。多查一次 `existsById` 仅发生在**异常路径**，对正常 P99 无影响（命中路径走快路径不查）。

**备选**：完全不查 `existsById`，日志只打 `orderId / userId`。代价：丢失越权探测的可观测性。

### D8. Service 层不依赖 Sa-Token：`userId` 由 Controller 显式传入

**选择**：`OrderService` 三个方法签名上 `userId` / `merchantId` 作为必传参数：
- `getForUser(Long userId, Long orderId)`
- `listMine(Long userId, Pageable pageable)`
- `listForMerchant(Long merchantId, Pageable pageable)`

Sa-Token 调用 (`StpUtil.getLoginIdAsLong()`) 仅在 `OrderController` 内发生；Controller 取到 userId 后显式传入 Service。

**理由**：
- 与项目既有惯例一致——`SessionService.getOrCreate(sessionId, merchantId, userId, channel)` / `SessionService.findByUserId(Long userId)` 等都是「Controller 取身份、Service 收参数」的模式
- `voice-shopping-business/pom.xml` 当前没有 sa-token 依赖，整个 business 模块零 `StpUtil` 调用；保持纯粹避免跨层耦合
- 方法签名上 `userId` 必传比"内部去 ThreadLocal 取"更彻底地体现「主体强制过滤」原则——任何调用方（Controller / 定时任务 / 测试）都必须显式提供身份才能调
- Service 单元测试无需 mock Sa-Token，直接传 `userId = 7L` 即可

**备选及为何不选**：
- **A. Service 内调 `StpUtil`**：需要给 business 模块加 sa-token 依赖；引入跨层耦合；测试要 mock SaToken；与 SessionService 惯例不一致
- **B. 注入既有 `CurrentUser` 组件**：`CurrentUser` 在 `voice-shopping-web` 模块（见 `merchant-data-isolation` spec），business → web 反向依赖违反 `web → business → ai → infrastructure` 依赖方向

### D6. JSONB 字段映射：与 `Product` 保持一致

`items` 用强类型 `List<OrderItemJson>`（V1 注释中的 schema 是稳定的，强类型有助于编译期检查 + IDE 补全）。`aiContext` 用 `Map<String, Object>`（schema 由 RecAgent 决定，仍在演进，弱类型更适配）。两者都用 Hibernate 6 的 `@JdbcTypeCode(SqlTypes.JSON)`，与 `Product.attributes` / `Product.imageUrls` 的写法一致。

### D7. `listForMerchant` 暂不暴露 HTTP 接口

Controller 层不提供 `/orders/merchant/{merchantId}` 之类的对外路径。`OrderService.listForMerchant` 仅作为 Service 层方法存在，调用方为可信内部代码（未来定时任务 / 管理脚本 / 商家后台接入时再加角色校验）。Javadoc 显式标注「内部 Service 方法，不暴露给 Controller，调用方需自行确认 merchantId 合法性」。

## Risks / Trade-offs

- **[软约束依赖人和 AI 的自觉]** → CLAUDE.md 章节 + Repository 类级 Javadoc 警告 + Code Review 检查；后续如出现实际越权事故，再升级为 ArchUnit / NoRepositoryBean
- **[`Page` 序列化未来可能不兼容]** → 一旦官方变更行为，集中替换为统一的 `PageResultDTO<T>` 包装；当前不预先抽象避免过度设计
- **[`existsById` 在异常路径多一次 SQL]** → 仅未命中分支触发；正常成功路径无影响。`existsById` 走 PK 索引，单次查询 < 1ms
- **[`status` 用 `String` 而非 enum]** → 与 DB CHECK 约束的取值集合解耦，但失去 IDE 枚举补全。当前阶段订单状态机尚未实现，引入 enum 容易与未来设计冲突；待状态流转能力落地时再统一引入 `OrderStatus` enum
- **[未对 `receiverPhone` 脱敏]** → 用户查看自己的订单，明文符合直觉；如未来引入"客服查任意订单"场景，再针对该路径单独脱敏
- **[商家维度返回所有 status]** → 当前不区分已删除/已取消订单，调用方自行筛选；未来 `listForMerchant` 演进时可加 `status` 入参
