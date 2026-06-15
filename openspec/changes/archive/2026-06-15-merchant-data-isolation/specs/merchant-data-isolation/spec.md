## ADDED Requirements

### Requirement: AppUser Entity 与 Repository

系统 SHALL 在 `voice-shopping-infrastructure` 模块下提供 `AppUser` JPA Entity，映射 `app_user` 表全部业务字段（id / merchantId / externalId / nickname / phone / avatarUrl / status / lastActiveAt / deletedAt / createdAt / updatedAt）。

系统 SHALL 提供 `AppUserRepository` 继承 `JpaRepository<AppUser, Long>`，并提供：
- `Optional<AppUser> findByPhoneAndDeletedAtIsNull(String phone)`：用于登录路径，phone 在系统内必须全局唯一。
- `List<AppUser> findAllByPhoneAndDeletedAtIsNull(String phone)`：用于 service 层的 fail-fast 校验（如果查出多条直接报错而不是静默选 first）。

#### Scenario: AppUser 实体映射 app_user 表
- **WHEN** 检查 `AppUser.java` 字段定义
- **THEN** 包含 id、merchantId、externalId、nickname、phone、avatarUrl、status、lastActiveAt、deletedAt、createdAt、updatedAt 共 11 个业务字段，且 `@Table(name = "app_user")`

#### Scenario: 按 phone 查询活跃用户
- **WHEN** 调用 `findByPhoneAndDeletedAtIsNull("13800138000")`
- **THEN** 返回该 phone 对应的未软删除 AppUser，最多一条

### Requirement: CurrentUser 组件

系统 SHALL 在 `voice-shopping-web` 模块下提供 `CurrentUser` Spring `@Component`，封装 Sa-Token 当前登录用户的访问入口，提供以下方法：

- `Long id()`：返回当前登录用户的 userId（`StpUtil.getLoginIdAsLong()`）。未登录时 SHALL 抛出 `cn.dev33.satoken.exception.NotLoginException`（沿用 Sa-Token 默认行为）。
- `boolean isLogin()`：当前线程是否已登录（`StpUtil.isLogin()`）。
- `boolean belongsToMerchant(Long merchantId)`：当前登录用户是否属于指定商家。仅供商家运营场景使用，**不在普通用户视角调用**。实现 SHALL 通过 `AppUserRepository.findById(currentUser.id())` 拿到 user 的 `merchant_id` 与入参比对。

`belongsToMerchant` 的 Javadoc MUST 显式标注"商家运营专用"。

#### Scenario: 已登录获取 userId
- **WHEN** Sa-Token 已对当前线程执行过 `StpUtil.login(123L)`，调用 `currentUser.id()`
- **THEN** 返回 `123L`

#### Scenario: 未登录调用 id 抛异常
- **WHEN** 当前线程未登录，调用 `currentUser.id()`
- **THEN** 抛出 `NotLoginException`

#### Scenario: belongsToMerchant 命中
- **WHEN** 用户 7 的 merchantId = 5，已登录为用户 7，调用 `belongsToMerchant(5L)`
- **THEN** 返回 `true`

#### Scenario: belongsToMerchant 未命中
- **WHEN** 用户 7 的 merchantId = 5，已登录为用户 7，调用 `belongsToMerchant(9L)`
- **THEN** 返回 `false`

### Requirement: AuthController 登录接口

系统 SHALL 提供 `POST /api/v1/auth/login` 接口，请求体 `LoginRequest(String phone)`（暂不接受密码字段），响应 `ApiResult<LoginResponse(String token)>`。

接口逻辑：

1. 通过 `AppUserRepository.findAllByPhoneAndDeletedAtIsNull(phone)` 查找用户。
2. 查到 0 条 → 抛 `NotFoundException("用户不存在")`。
3. 查到 ≥ 2 条 → 抛 `IllegalStateException("phone 不唯一: " + phone)`，并以 ERROR 级别记录所有命中 userId 便于排查。**不允许静默选 first**。
4. 查到 1 条 → `StpUtil.login(user.getId())`，返回 `StpUtil.getTokenValue()`。

本接口仅校验 phone 字段是否对应一条 AppUser 记录，**不做密码校验**，仅供本版本跑通流程。

#### Scenario: phone 唯一登录成功
- **WHEN** 数据库中 phone="13800138000" 对应 1 条 AppUser
- **THEN** 接口返回 200 + 非空 token，且 `StpUtil.getLoginIdByToken(token)` 能解出该 userId

#### Scenario: phone 不存在
- **WHEN** phone="13900000000" 数据库中查无匹配
- **THEN** 接口返回 404，`code=404`，`msg` 包含 "用户不存在"

#### Scenario: phone 不唯一 fail-fast
- **WHEN** phone="13800138000" 在 app_user 表里有 2 条未删除记录
- **THEN** 接口返回 500，日志中 ERROR 级别打印所有命中的 userId 列表

### Requirement: WebSocket Sub-Protocol Token 鉴权

系统 SHALL 提供 `AuthHandshakeInterceptor implements HandshakeInterceptor`，替换原有的 `VoiceHandshakeInterceptor`。前端连接契约：`new WebSocket(url, ['Bearer-' + token])`，token 通过 `Sec-WebSocket-Protocol` 请求头传入；`sessionId` 通过 query 参数传入。

`beforeHandshake` 流程（任一步失败 SHALL 拒绝握手并 `return false`）：

1. 读取 `Sec-WebSocket-Protocol` 请求头；缺失或不以 `"Bearer-"` 开头时设置响应状态为 401。
2. 剥离 `"Bearer-"` 前缀拿到 token。
3. 调用 `StpUtil.getLoginIdByToken(token)`，结果为 null 时设置响应状态为 401。
4. 读取 query 参数 `sessionId`；缺失或空时设置响应状态为 400。
5. `attributes.put("userId", userId)`、`attributes.put("sessionId", sessionId)`。
6. **必须**在响应头中回写 `Sec-WebSocket-Protocol: Bearer-{token}`，否则浏览器按 RFC 6455 §4.2.2 拒绝握手。
7. `return true`。

旧的 `VoiceHandshakeInterceptor` 通过 query `?userId=` 取身份的路径 SHALL 删除，不保留兼容。`WebSocketConfig.registerWebSocketHandlers` SHALL 注册 `AuthHandshakeInterceptor`。

#### Scenario: 合法 token 握手成功
- **WHEN** 客户端 `new WebSocket("ws://host/ws/voice?sessionId=sess-1", ['Bearer-' + validToken])`
- **THEN** 握手成功，session.attributes 含 userId（由 token 解析）和 sessionId="sess-1"
- **THEN** 响应头包含 `Sec-WebSocket-Protocol: Bearer-{validToken}`

#### Scenario: 缺失 Sec-WebSocket-Protocol 头拒绝
- **WHEN** 客户端不传 sub-protocol（普通 `new WebSocket(url)`）
- **THEN** 握手返回 401，`return false`

#### Scenario: token 无效拒绝
- **WHEN** sub-protocol 为 `"Bearer-INVALID_TOKEN"`，`StpUtil.getLoginIdByToken` 返回 null
- **THEN** 握手返回 401，`return false`

#### Scenario: 缺失 sessionId 拒绝
- **WHEN** 合法 token 但 URL 中无 `sessionId` query 参数
- **THEN** 握手返回 400，`return false`

#### Scenario: 旧 query userId 路径已下线
- **WHEN** 客户端 `new WebSocket("ws://host/ws/voice?userId=1&sessionId=sess-1")`（无 sub-protocol）
- **THEN** 握手 401（缺失 sub-protocol），后端 SHALL NOT 信任 query 中的 userId

### Requirement: Channel 枚举与 SessionScope Record

系统 SHALL 在 `com.voiceshopping.common.dto.session` 包下定义 `Channel` 枚举：`HOME_ENTRY` / `PRODUCT_PAGE` / `MERCHANT_HOME` / `SEARCH_FALLBACK`。

系统 SHALL 在同一包下定义 `SessionScope` Record：

```java
public record SessionScope(
        Long userId,
        List<Long> allowedMerchantIds,  // null/empty = 全平台
        Long boundProductId             // PRODUCT_PAGE 必填
) {
    public boolean isPlatformWide() {
        return allowedMerchantIds == null || allowedMerchantIds.isEmpty();
    }
    public static SessionScope platformWide(Long userId) {
        return new SessionScope(userId, List.of(), null);
    }
}
```

#### Scenario: isPlatformWide 在 null 时为 true
- **WHEN** `new SessionScope(7L, null, null).isPlatformWide()`
- **THEN** 返回 `true`

#### Scenario: isPlatformWide 在 empty list 时为 true
- **WHEN** `new SessionScope(7L, List.of(), null).isPlatformWide()`
- **THEN** 返回 `true`

#### Scenario: isPlatformWide 在 has merchant 时为 false
- **WHEN** `new SessionScope(7L, List.of(5L), null).isPlatformWide()`
- **THEN** 返回 `false`

### Requirement: SessionScopeCache Redis 旁路缓存

系统 SHALL 在 `voice-shopping-business` 模块下提供 `SessionScopeCache` 组件，提供 `put(sessionId, scope)` 与 `Optional<SessionScope> get(sessionId)` 方法。

- Redis key 为 `RedisKeys.scope(sessionId)`，定义在 `RedisKeys` 类中（前缀 `vs:scope:`）。
- 序列化使用 `ObjectMapper`。
- `put` MUST 使用与 `voice-shopping.memory.session-state.ttl`（默认 30m）一致的 TTL，调用 `set(key, json, ttl)` 三参重载。
- Redis 异常 SHALL 仅 WARN 日志，不向调用方传播。
- `get` 在 key 不存在或 Redis 异常时 SHALL 返回 `Optional.empty()`，由调用方做兜底。

#### Scenario: put 写入带 TTL
- **WHEN** 调用 `cache.put("sess-1", scope)`
- **THEN** Redis key `vs:scope:sess-1` 存在，TTL ≈ 30 分钟，值为 scope 的 JSON

#### Scenario: get 命中
- **WHEN** `cache.put("sess-1", scope)` 后调用 `cache.get("sess-1")`
- **THEN** 返回 `Optional.of(scope)`，equals 入参 scope

#### Scenario: get 未命中返回 empty
- **WHEN** 调用 `cache.get("never-set")`
- **THEN** 返回 `Optional.empty()`

#### Scenario: Redis 异常时 get 不抛异常
- **WHEN** Redis 连接失败时调用 `cache.get("sess-1")`
- **THEN** 返回 `Optional.empty()`，WARN 日志记录异常

### Requirement: SessionController /session/start 接口

系统 SHALL 提供 `POST /api/v1/session/start` 接口，请求体：

```java
public record StartSessionRequest(
        String sessionId,        // @NotBlank
        Channel channel,         // @NotNull
        Long merchantId,         // MERCHANT_HOME 时必填
        Long boundProductId      // PRODUCT_PAGE 时必填
) {}
```

响应：`ApiResult<SessionScope>`。

接口逻辑：

1. 通过 `currentUser.id()` 取 userId（未登录直接 Sa-Token 异常 401）。
2. 按 channel 校验并解析 scope：
   - `HOME_ENTRY` / `SEARCH_FALLBACK`：忽略 merchantId / boundProductId，scope = `(userId, [], null)`（全平台）。
   - `PRODUCT_PAGE`：`boundProductId` 必填且 `productRepository.findById` 必须存在；scope = `(userId, [product.merchantId], boundProductId)`。
   - `MERCHANT_HOME`：`merchantId` 必填；scope = `(userId, [merchantId], null)`。
3. 调用 `sessionService.getOrCreate(sessionId, scope.allowedMerchantIds 中第一个或 null, userId, channel.name())` 幂等落 session 表（沿用画像章节实现的方法）。
4. 调用 `scopeCache.put(sessionId, scope)`。
5. 返回 scope。

校验失败 SHALL 抛 `IllegalArgumentException`（统一处理为 400），`PRODUCT_PAGE` 的 boundProductId 不存在时 SHALL 抛 `NotFoundException`（404）。

#### Scenario: HOME_ENTRY 全平台
- **WHEN** POST `/api/v1/session/start` 入参 `{sessionId:"s1", channel:"HOME_ENTRY"}`，已登录用户 7
- **THEN** 返回 `{userId:7, allowedMerchantIds:[], boundProductId:null}`
- **THEN** session 表存在 id="s1", user_id=7, channel="HOME_ENTRY", merchant_id=null
- **THEN** Redis `vs:scope:s1` 存在

#### Scenario: PRODUCT_PAGE 锁定到商品所属商家
- **WHEN** 商品 88 的 merchantId=5，POST 入参 `{sessionId:"s2", channel:"PRODUCT_PAGE", boundProductId:88}`
- **THEN** 返回 `{userId:7, allowedMerchantIds:[5], boundProductId:88}`
- **THEN** session 表存在 id="s2", merchant_id=5, bound_product_id=88

#### Scenario: PRODUCT_PAGE 缺 boundProductId 报错
- **WHEN** POST 入参 `{sessionId:"s3", channel:"PRODUCT_PAGE"}`（无 boundProductId）
- **THEN** 返回 400，msg 含 "boundProductId"

#### Scenario: PRODUCT_PAGE boundProductId 不存在报错
- **WHEN** 商品 99999 不存在，POST 入参 `{sessionId:"s4", channel:"PRODUCT_PAGE", boundProductId:99999}`
- **THEN** 返回 404

#### Scenario: MERCHANT_HOME 锁定商家
- **WHEN** POST 入参 `{sessionId:"s5", channel:"MERCHANT_HOME", merchantId:5}`
- **THEN** 返回 `{userId:7, allowedMerchantIds:[5], boundProductId:null}`

#### Scenario: MERCHANT_HOME 缺 merchantId 报错
- **WHEN** POST 入参 `{sessionId:"s6", channel:"MERCHANT_HOME"}`
- **THEN** 返回 400，msg 含 "merchantId"

#### Scenario: 重复调用 start 幂等
- **WHEN** 同一 sessionId 调用 `/session/start` 两次
- **THEN** 第二次不创建新 session 行（getOrCreate 幂等），但 Redis scope 被覆盖写入

### Requirement: ScopeFilterBuilder

系统 SHALL 在 `voice-shopping-infrastructure.vector` 包下提供 `ScopeFilterBuilder`，将 `SessionScope` 转换为 `SqlFilterBuilder.Filter`：

- `scope == null` 或 `scope.isPlatformWide()` → 返回 `Filter.EMPTY`。
- 否则返回 `Filter("merchant_id IN (?, ?, ...)", scope.allowedMerchantIds())`，占位符数量与 list size 匹配。

输出的 Filter MUST 可直接用 `SqlFilterBuilder.merge(genericFilter, scopeFilter)` 与现有 slot filter 合流。

#### Scenario: null scope 返回 EMPTY
- **WHEN** `scopeFilterBuilder.build(null)`
- **THEN** 返回 `Filter.EMPTY`

#### Scenario: 全平台返回 EMPTY
- **WHEN** `scopeFilterBuilder.build(new SessionScope(7L, List.of(), null))`
- **THEN** 返回 `Filter.EMPTY`

#### Scenario: 单商家生成 IN
- **WHEN** `scopeFilterBuilder.build(new SessionScope(7L, List.of(5L), null))`
- **THEN** 返回 `Filter("merchant_id IN (?)", List.of(5L))`

#### Scenario: 多商家生成 IN
- **WHEN** `scopeFilterBuilder.build(new SessionScope(7L, List.of(5L, 9L, 12L), null))`
- **THEN** 返回 `Filter("merchant_id IN (?, ?, ?)", List.of(5L, 9L, 12L))`

#### Scenario: 与 generic filter merge
- **WHEN** generic filter `("price <= ?", [500])`，scope filter `("merchant_id IN (?)", [5])`，调用 `sqlFilterBuilder.merge(generic, scope)`
- **THEN** 返回 `Filter("price <= ? AND merchant_id IN (?)", [500, 5])`

### Requirement: 推荐链路强制叠加 Scope 过滤

`RecommendOrchestrator.recommend(sessionId, userId, utterance, slots)` 与 `ParallelRecommendService.recommend(...)` SHALL 在构造 SQL Filter 时强制叠加 `ScopeFilterBuilder` 生成的 scope filter，覆盖**所有**进入 `ProductVectorService.search` 的代码路径（包括渐进 fallback 的每一次重试）。

实现位置：在 `RecommendCandidateRetriever.buildFilter(slots)` 之后、`retrieve(...)` 之前，由编排层（`RecommendOrchestrator` / `ParallelRecommendService`）通过 `SqlFilterBuilder.merge(genericFilter, scopeFilter)` 合流，再传入 retriever。retriever 内部不感知 scope。

`SearchController GET /api/v1/search` SHALL 新增可选 query 参数 `sessionId`：

- 非空：通过 `scopeCache.get(sessionId)` 取 scope（miss 走兜底，见下条），叠加过滤。
- 空：保持现有 smoke test 行为不动（无 scope）。

调试入口（`RecommendDebugController` / `ChatDebugController` / `EmotionDebugController` 等）SHALL NOT 强制叠加 scope —— 调试视野不被 scope 遮蔽是显式选择。

#### Scenario: RecommendOrchestrator 叠加单商家 scope
- **WHEN** sessionId="s1" 的 scope=`(7, [5], null)`，调用 `recommend("s1", 7, "跑鞋", {budget:500})`
- **THEN** 最终传入 `productVectorService.search` 的 filter clause 包含 `merchant_id IN (?)` 子句，params 包含商家 id 5

#### Scenario: ParallelRecommendService 叠加 scope
- **WHEN** 同上输入
- **THEN** 与 RecommendOrchestrator 在同一输入下产生等价的 filter（除 LLM 文本外结果一致）

#### Scenario: 全平台 scope 不影响原有行为
- **WHEN** sessionId="s1" 的 scope=`(7, [], null)`（全平台），调用 recommend
- **THEN** filter clause 不包含 `merchant_id` 片段，与本版本前的行为等价

#### Scenario: SearchController 不传 sessionId 保留旧行为
- **WHEN** `GET /api/v1/search?q=跑鞋` 不传 sessionId
- **THEN** 检索 SQL 不含 merchant_id 过滤

#### Scenario: SearchController 传 sessionId 叠加 scope
- **WHEN** `GET /api/v1/search?q=跑鞋&sessionId=s1`，scope=`(7, [5], null)`
- **THEN** 检索 SQL 含 `merchant_id IN (?)`，参数为 5

### Requirement: Scope 缓存 Miss 降级语义

当 `scopeCache.get(sessionId)` 返回 `Optional.empty()` 时（缓存丢失 / TTL 过期 / Redis 故障），所有强制叠加 scope 的入口（`RecommendOrchestrator` / `ParallelRecommendService` / `SearchController` 带 sessionId 时）SHALL：

1. 记 WARN 级别日志，文案统一为 `"Scope cache miss for sessionId={}, falling back to platform-wide"`。
2. 使用兜底 scope = `SessionScope.platformWide(userId)`（即 `(userId, [], null)`），SHALL NOT 抛异常。
3. 后续过滤等价于全平台 — 不附加 `merchant_id` 子句。

降级路径 SHALL NOT 静默记 INFO/DEBUG，必须 WARN 以便监控聚合。

#### Scenario: 缓存未命中走全平台兜底
- **WHEN** sessionId="s-old" 的 scope 在 Redis 中已过期，调用 `recommend("s-old", 7, ...)`
- **THEN** 检索 SQL 不含 `merchant_id` 过滤
- **THEN** 日志中存在 WARN "Scope cache miss for sessionId=s-old"

#### Scenario: Redis 故障走全平台兜底
- **WHEN** Redis 不可用时调用 recommend
- **THEN** 不抛异常，正常返回推荐结果，等价于全平台

### Requirement: V6 Flyway 迁移扩 channel CHECK 约束

系统 SHALL 提供 `V6__add_merchant_home_channel.sql` 迁移脚本，扩展 `session.channel` 的 CHECK 约束以接受 `MERCHANT_HOME`：

```sql
ALTER TABLE session DROP CONSTRAINT session_channel_check;
ALTER TABLE session ADD CONSTRAINT session_channel_check
    CHECK (channel IN ('HOME_ENTRY', 'PRODUCT_PAGE', 'MERCHANT_HOME', 'SEARCH_FALLBACK'));
```

V1-V5 SHALL NOT 被修改。

#### Scenario: V6 后插入 MERCHANT_HOME 行不被 CHECK 顶回
- **WHEN** V6 已执行，INSERT INTO session (..., channel) VALUES (..., 'MERCHANT_HOME')
- **THEN** 插入成功

#### Scenario: 非法 channel 仍被拒绝
- **WHEN** V6 已执行，INSERT channel='INVALID'
- **THEN** PG 抛 check_violation

### Requirement: ProductRepository scope 感知方法

系统 SHALL 在 `ProductRepository` 上新增 `findByIdInWithScope(List<Long> ids, List<Long> merchantIds)` 查询：

- `merchantIds` 为 null 或 empty 时 SHALL 等价于 `findAllById(ids)` —— 不附加商家约束。
- 否则 SHALL 通过 `@Query` 显式声明 `WHERE id IN :ids AND merchant_id IN :merchantIds AND deleted_at IS NULL`。

返回类型为 `List<Product>`。

#### Scenario: 无 scope 全集查询
- **WHEN** `findByIdInWithScope(List.of(1L,2L,3L), null)`
- **THEN** 返回 ids 对应的所有未删除商品（无 merchant 约束）

#### Scenario: 带 scope 过滤
- **WHEN** `findByIdInWithScope(List.of(1L,2L,3L), List.of(5L))`，其中商品 1 merchantId=5、商品 2 merchantId=9、商品 3 merchantId=5
- **THEN** 返回商品 1 和 3（商品 2 被 scope 过滤）

### Requirement: RedisKeys 新增 scope 命名空间

`RedisKeys` 类 SHALL 新增 `scope(String sessionId)` 静态方法，返回 `vs:scope:{sessionId}`，与 `vs:session:` / `vs:short_memory:` 同级且使用统一前缀。

#### Scenario: scope key 格式
- **WHEN** 调用 `RedisKeys.scope("sess-1")`
- **THEN** 返回 `"vs:scope:sess-1"`
