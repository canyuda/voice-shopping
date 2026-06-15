## Why

当前系统在多商家维度上完全敞开：WebSocket 握手只读 query 里的 `userId`，前端写什么后端信什么；商品向量检索 (`ProductVectorService.search`) 在代码里仍挂着 `// TODO: add merchant_id filter for multi-tenancy`，从商详页 / 商家店铺首页进来的会话推荐到的商品不受任何商家边界约束。本版本要把"用户身份 + 会话商家范围 + 检索过滤"三件事一次织进端到端链路，把多租户隔离从口头约定变成系统不变量。

## What Changes

- **新增** Sa-Token 登录入口 `POST /api/v1/auth/login`：用 phone 字段做用户名校验（不校验密码，先把流程跑通），`StpUtil.login(userId)` 后返回 token。
- **新增** `CurrentUser` 组件：封装 Sa-Token 取 `id() / isLogin() / belongsToMerchant(Long)`，`belongsToMerchant` 仅限商家运营场景使用。
- **新增** `app_user` 的 JPA Entity 和 Repository（仓储层目前只有 `user_profile_*`，没有 `app_user` 直接映射）。
- **BREAKING** WebSocket 鉴权契约变更：从 query `?userId=` 改为 `Sec-WebSocket-Protocol: Bearer-{token}` 头校验。`AuthHandshakeInterceptor` 解 token → userId，握手时**必须回写** sub-protocol 响应头（RFC 6455 要求），失败则 401 拒绝握手。query 里的 `userId` 字段移除，不做兼容。
- **新增** 会话进入渠道枚举 `Channel`：`HOME_ENTRY / PRODUCT_PAGE / MERCHANT_HOME / SEARCH_FALLBACK`。其中 `MERCHANT_HOME` 是新增的渠道值，需要 V6 迁移扩 `session.channel` CHECK 约束。
- **新增** `SessionScope` record + `SessionScopeCache` 组件：scope = (userId, allowedMerchantIds, boundProductId)，`allowedMerchantIds == null/empty` 表示全平台。Redis key `vs:scope:{sessionId}`，TTL 与会话 (`vs:session:`) 对齐。
- **新增** `POST /api/v1/session/start` 接口：根据 channel 校验必填字段、解析 scope、幂等落 session 表、写 Redis scope 缓存，返回 `SessionScope`。
- **新增** `ScopeFilterBuilder`：将 `SessionScope` 转成 `SqlFilterBuilder.Filter`（`merchant_id IN (?,?,?)`），全平台时返回 `Filter.EMPTY`，与现有 slot filter 通过 `SqlFilterBuilder.merge()` 合流。
- **修改** `ProductRepository`：新增 `findByIdInWithScope(ids, merchantIds)`，scope 为空时不加约束。
- **修改** `RecommendOrchestrator / ParallelRecommendService / RecommendCandidatesService / SearchController`：所有"拼参数查产品库"的入口统一通过 `SessionScopeCache.get(sessionId)` 取 scope 并叠加过滤；scope 缓存 miss 时**降级为"仅该用户、全平台"**（=空 allowedMerchantIds），不抛异常，但记 WARN 日志，避免老会话 / Redis 故障时直接挂。
- **新增** Flyway V6 迁移：扩 `session.channel` CHECK 约束加上 `MERCHANT_HOME`。
- **新增** `RedisKeys.scope(sessionId)`：集中管理新 key 命名空间。

## Capabilities

### New Capabilities
- `merchant-data-isolation`: 多商家数据隔离的端到端契约——从用户登录、WebSocket 握手鉴权、会话商家范围解析、到检索层强制过滤的不变量集合。

### Modified Capabilities
- `voice-websocket`: 握手鉴权从 query userId 改为 Sub-Protocol token，断开旧契约（`WebSocketConfig` 端点注册的 interceptor 实例换成 `AuthHandshakeInterceptor`）。
- `rec-orchestration`: 推荐编排（`RecommendOrchestrator` / `ParallelRecommendService`）在已有 generic filter 之上**强制叠加** scope filter；缓存 miss 时降级为"全平台兜底"。
- `product-vector-search`: 检索 SQL 中 `// TODO: add merchant_id filter for multi-tenancy` 注释由"占位 TODO"明确改为"由调用方通过 extraFilter 传入 scope 过滤"，行为契约由 spec 显式承接。

## Impact

**代码**：
- 新增模块：`voice-shopping-web/auth/*`、`voice-shopping-web/session/*`、`voice-shopping-business/scope/*`、`voice-shopping-infrastructure/repository/{AppUserRepository, entity/AppUser}`。
- 修改：`VoiceHandshakeInterceptor`（重写），`WebSocketConfig`（注入 Sa-Token 依赖），`VoiceWebSocketHandler`（不再依赖 query userId），`RecommendCandidatesService` / `RecommendOrchestrator` / `ParallelRecommendService`（注入 `SessionScopeCache` + `ScopeFilterBuilder`），`SearchController`（带 sessionId 时叠加 scope），`SqlFilterBuilder`（无变更，仅复用 merge）。
- 新增类：`Channel` (enum)、`SessionScope` (record)、`StartSessionRequest` (record)、`SessionScopeCache`、`ScopeFilterBuilder`、`AuthController`、`SessionController`、`AppUser` entity、`AppUserRepository`、`CurrentUser`。

**数据**：
- Flyway V6 扩 `session.channel` CHECK 约束。
- Redis 引入 `vs:scope:{sessionId}` 命名空间。

**外部契约（破坏性）**：
- WebSocket 客户端必须改为 `new WebSocket(url, ['Bearer-' + token])` 形式连接，旧 `?userId=` 路径下线。
- 调用方在建立 WebSocket 之前必须先调用 `POST /api/v1/auth/login` + `POST /api/v1/session/start`。

**依赖**：无新增三方依赖，Sa-Token 已在 `voice-shopping-web/pom.xml` 中。

**风险**：
- Sub-Protocol 响应头回写遗漏 → 浏览器握手失败（必须在 spec 与 task 显式标注）。
- scope miss 降级为"仅该用户、全平台"，弱化了商家隔离的强一致保证；接受这一权衡是为了避免老会话因 Redis 故障直接挂。需在 spec 中明确该降级路径并由 WARN 日志兜底审计。
