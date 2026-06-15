## Context

当前 `voice-shopping` 项目的多租户隔离基础设施只完成到了"表里有 `merchant_id` 列"这一层。再往上：

- WebSocket 握手在 `VoiceHandshakeInterceptor.beforeHandshake` 直接读 `?userId=` 当作身份，前端写什么后端信什么 —— 任何客户端都能 `?userId=1` 冒充任意用户。
- 商品向量检索 `ProductVectorService.search` 仍挂着一行 `// TODO: add merchant_id filter for multi-tenancy`，从商详页 / 商家店铺首页进来的会话推荐时不加任何商家边界。
- 项目里已经依赖 Sa-Token 1.45.0（`voice-shopping-web/pom.xml`），但还没有 `StpUtil.login(...)` 调用点 —— 整套登录流程是空的。
- `session.channel` 的 PG CHECK 约束目前只接受 `HOME_ENTRY / PRODUCT_PAGE / SEARCH_FALLBACK`，新枚举里要加的 `MERCHANT_HOME` 会被数据库直接顶回来。
- `RedisKeys` 里没有 `vs:scope:` 前缀；`SqlFilterBuilder` 当前生产 `Filter(clause, params)` 的范式很干净，scope 过滤要走同一格式 + `merge()` 合流，不要单开一条路径。

利益相关者：本版本主要影响业务编排层（`OrchestratorService` / `RecommendOrchestrator` / `ParallelRecommendService`）、检索层（`ProductVectorService` / `SearchController`）、WebSocket 接入层（`VoiceWebSocketHandler` / `VoiceHandshakeInterceptor`）以及前端（连接契约破坏，必须改 token-based）。

## Goals / Non-Goals

**Goals:**

- 把"用户身份 → 会话商家范围 → 检索过滤"组成一条不可绕过的纵向链路，任何"拼参数查产品库"的入口都通过同一个 `ScopeFilterBuilder` 收口。
- WebSocket 握手切换到 Sub-Protocol token 鉴权，前端契约：`new WebSocket(url, ['Bearer-' + token])`，旧 query `userId` 路径**直接下线**，不留兼容尾巴。
- 通过显式 `POST /api/v1/session/start` 接口把 `Channel → SessionScope` 的解析做透：合法性校验（`PRODUCT_PAGE` 必须有 `boundProductId` 且商品存在；`MERCHANT_HOME` 必须有 `merchantId`）、幂等落 `session` 表、写 Redis 旁路缓存。
- scope 缓存丢失时**降级为"仅该用户、全平台"**（即 scope = (userId, [], null)），不抛异常、不挂老会话，但记 WARN 日志兜底审计。
- 给 `session.channel` 写 V6 Flyway 迁移把 `MERCHANT_HOME` 加入 CHECK 约束；保持 V1-V5 不可变。

**Non-Goals:**

- 不做密码校验：`AuthController` 只做 phone 字段查表 + `StpUtil.login`，仅为跑通流程。后续真实登录（密码哈希 / 验证码 / OAuth）单独立项。
- 不动 FAQ 检索 (`FaqVectorService.searchBest/searchTopN`)：当前签名是单 `merchantId`，要扩成 `List<Long>` 才能配合 `SessionScope`。本版本 backlog 记着，但不做。
- 不在 `session` 表上物化 `allowed_merchant_ids` —— scope 是会话维度的临时状态，PG 持久化的不变量仍然是 `(merchant_id, bound_product_id, channel)`。scope cache 是这三者的**派生**，丢失后从用户维度兜底，不是从 PG 重建。
- 不动现有调试接口的 scope 行为（`RecommendDebugController` / `EmotionDebugController` 等）：调试入口应当看到真实数据形态，不能让 scope 把视野遮住。这个版本只在生产路径强制；调试接口可以传 sessionId 时**自愿**叠加 scope，不传则保留无 scope 行为。
- 不引入新的三方依赖。

## Decisions

### Decision 1: 登录接口的"用户名"映射到 `phone` 字段（Q1=B）

`app_user` 表上没有 `username/password` 列。候选项里只有 `phone` 已经有 `(merchant_id, phone)` 上的复合索引，业务上唯一性可控。`nickname` 不唯一（会查出多条），`external_id` 又得先指定 `merchant_id`，登录时用户没法填。

**rationale**：
- 选 `phone` 是当前表结构的最低改动路径，且未来正式做密码登录时 phone 仍然是合理的"用户名"。
- 多 merchant 共用同一手机号的情况：本版本约束 phone 在系统内全局唯一（不带 merchant_id 维度），如果查出多条直接 `IllegalStateException` fail-fast，**绝不返回任意一条**。这条约束写进 spec 而不是数据库 unique 约束（避免侵入 V6 迁移），由 service 层 fail-fast 保证。

**alternatives considered**：
- D（新增 `login_name` 字段）：方案最干净，但要再写一次 V6 迁移、还要改 entity / Repository / 数据填充脚本，成本明显高于"先用 phone 跑通流程"。
- A（用 `nickname`）：不唯一，要么加 unique 约束（实质等于 D 的成本）、要么取 first（违背项目 fail-fast 原则）。

### Decision 2: WebSocket 鉴权用 Sub-Protocol header，握手响应必须回写

前端使用 `new WebSocket(url, ['Bearer-' + token])` 把 token 塞进 `Sec-WebSocket-Protocol` 头。`AuthHandshakeInterceptor.beforeHandshake` 流程：

```
1. 读 request.getHeaders().getFirst("Sec-WebSocket-Protocol")
2. 必须以 "Bearer-" 开头，剥前缀拿 token；不符则 401 + return false
3. StpUtil.getLoginIdByToken(token) → userId；解析失败 401 + return false
4. 必须从 query 取 sessionId；缺失 400 + return false
5. attributes.put(ATTR_USER_ID, userId); attributes.put(ATTR_SESSION_ID, sessionId)
6. response.setHeaders().add("Sec-WebSocket-Protocol", "Bearer-" + token)  ← RFC 6455 强制
7. return true
```

**rationale**：
- RFC 6455 §4.2.2：服务端 101 响应必须**确认**客户端提议的 sub-protocol，否则浏览器立刻断开。这条是教科书没明写但**会让前端连不上**的坑，必须写进 spec 作为 hard requirement。
- 不用 cookie：跨域时浏览器会因 SameSite 策略不发 cookie，用户体验直接坏掉。
- 不用 query token：URL 会被代理 / 日志记录，token 泄露面太大。

**alternatives considered**：
- WS Header 自定义：浏览器 `WebSocket` API **无法**设置任意 header，只能塞 sub-protocol，物理上没有别的选择。
- 单独的 `/api/v1/ws-ticket` 换一次性 ticket：更安全但要多一次握手，本期接受 token 直传的成本。

### Decision 3: `SessionScopeCache` miss 时降级为"仅该用户、全平台"（Q3）

```
ParallelRecommendService.recommend(...)
   ├─ scope = scopeCache.get(sessionId)
   ├─ if scope == null:
   │     log.warn("Scope cache miss for sessionId={}, falling back to platform-wide", sessionId)
   │     scope = SessionScope.platformWide(userId)   // (userId, [], null)
   └─ filter = ScopeFilterBuilder.build(scope)        // EMPTY
```

**rationale**：
- 老会话 + Redis 故障 + Redis 重启都是已知会发生的场景；让推荐链路因为这个直接挂，UX 比"丢一次商家隔离"差得多。
- 记 WARN 而不是 INFO/DEBUG：SRE 可以从日志聚合里发现 scope miss 的频率，超阈值时再做强一致。
- 不试图从 PG `session` 表重建：原因见 Non-Goals —— `HOME_ENTRY` 与 `SEARCH_FALLBACK` 都对应 `allowedMerchantIds = []`，但商详页的 `MERCHANT_HOME` 重建时如果 session 表里 `merchant_id` 缺失则无法还原；与其分支处理不如统一兜底。

**alternatives considered**：
- A（fail-fast 抛异常）：UX 差，老会话直接挂。
- C（PG 重建）：正确性更强，但 `session` 表的 `merchant_id` 是 nullable，重建分支有多种边界，本期不值得做。

### Decision 4: scope 过滤覆盖范围 — 所有"拼参数查产品库"的入口（Q4）

扫了一遍代码，受影响的入口：

| 入口 | 当前是否过 scope | 本版本动作 |
|------|------------------|------------|
| `RecommendOrchestrator.recommend` | ❌ | ✅ 必加 |
| `ParallelRecommendService.recommend` | ❌ | ✅ 必加 |
| `RecommendCandidatesService.fetchCandidates` | ❌ | ✅ 在上层 build filter 时合流，service 内部不需要直接感知 scope |
| `SearchController /api/v1/search` | ❌ | ✅ 加 sessionId query 参数；非空时叠加 scope，空时保留旧行为（smoke test 兼容） |
| `FaqVectorService.searchBest/searchTopN` | 已经按 `IN(0, mid)` | ❌ 不动（API 是单 merchantId，扩 scope 要改签名 + 改调用方，本期 backlog） |
| `RecommendDebugController` | ❌ | ❌ 不动（调试入口要看真实数据形态，传 sessionId 自愿叠加） |

**实现路径**：把 scope 拼接逻辑放在**编排层**（`RecommendOrchestrator` / `ParallelRecommendService` / `SearchController`）：在它们已经构造 `SqlFilterBuilder.Filter` 的位置，紧接着 `merge(genericFilter, scopeFilter)`。`RecommendCandidatesService` / `ProductVectorService` 保持透明，不感知 scope。

**alternatives considered**：
- 在 `ProductVectorService.search` 内部强制注入 scope：会破坏现有职责（vector service 不应该感知会话），且 smoke test 调用就得伪造 sessionId，不合算。
- 在 SQL 模板里直接 `WHERE merchant_id IN (?)`：scope 全平台时 SQL 会变成 `merchant_id IN (NULL)` 或要 `IS NULL OR ...` 兜底，复杂度高于过滤合流。

### Decision 5: `SessionScope` 为 null 的语义 = 全平台（Q5）

`ScopeFilterBuilder.build(null)` 返回 `Filter.EMPTY`，等同于 `isPlatformWide()`。`null` 视为"未提供 scope 信息"，与 Decision 3 兜底链路对齐。

**rationale**：减少调用方的 null check 心智负担；与 `SqlFilterBuilder.fromSlots(null)` 的现有惯例一致。

### Decision 6: V6 迁移仅扩 `session.channel` CHECK，不改其他

```sql
-- V6__add_merchant_home_channel.sql
ALTER TABLE session DROP CONSTRAINT session_channel_check;
ALTER TABLE session ADD CONSTRAINT session_channel_check
    CHECK (channel IN ('HOME_ENTRY', 'PRODUCT_PAGE', 'MERCHANT_HOME', 'SEARCH_FALLBACK'));
```

**rationale**：
- V6 是新版本号，符合"已执行的迁移脚本不可改"约束。
- 不在本期给 `app_user` 加 `login_name` 列（见 Decision 1）。
- 不在本期给 `session` 加 `allowed_merchant_ids` JSONB 列（见 Non-Goals）。

### Decision 7: `RedisKeys.scope(sessionId)` 命名空间 + TTL 与 session 对齐

```java
public static String scope(String sessionId) { return PREFIX + "scope:" + sessionId; }
```

TTL 用与 `vs:session:` 相同的 `voice-shopping.memory.session-state.ttl`（默认 30m），确保 scope 缓存与会话状态同进同出。SessionStartService 写入 scope 时 MUST 用 `set(key, json, ttl)` 三参重载。

### Decision 8: `CurrentUser` 是组件不是工具类

`CurrentUser` 注册为 Spring `@Component`（request 域可不强制，因为 Sa-Token 自己有 ThreadLocal），提供 `id() / isLogin() / belongsToMerchant(Long)`。`belongsToMerchant` 当前实现：通过 `AppUserRepository.findById(currentUser.id())` 拿到 `merchant_id` 比对。

**rationale**：
- `belongsToMerchant` 仅供商家运营场景使用（spec 文案里要明确"非用户视角"），但实现上要存在，否则后面接商家后台时还得回头补。
- 包装成 `@Component` 而不是 `static` 工具类：方便测试 mock，也符合项目"优先函数式 + 组件化"的风格。

## Risks / Trade-offs

- **[scope miss 降级弱化隔离]** → 接受为已知权衡（Decision 3）。WARN 日志 + 监控阈值审计；超阈值时单独立项做 PG 重建。
- **[Sub-Protocol 响应头遗漏]** → 写进 spec 作为 hard requirement（Decision 2 第 6 步）；新增集成测试断言握手响应包含 `Sec-WebSocket-Protocol: Bearer-{token}` 头。
- **[V6 迁移与正在运行的服务]** → `ALTER TABLE ... DROP/ADD CONSTRAINT` 是 PG 在线 DDL，但会取 ACCESS EXCLUSIVE LOCK 短时间。生产环境部署时必须在低峰期。dev / staging 直接跑。
- **[phone 全局唯一约束在 service 层而非 DB 层]** → 风险：测试数据 / 旧数据可能违反，service fail-fast 时报错。Mitigation：登录接口收到多条记录时记 ERROR 日志便于排查，不静默选 first。
- **[WebSocket 协议破坏]** → 前端必须同步改造。Mitigation：在 proposal 标记 BREAKING，部署前与前端对齐升级窗口；后端发现旧 query `?userId=` 时不识别（前端会因解析不到 sessionId 报 400）即是隐式提示。
- **[MERCHANT_HOME 渠道下没有 boundProductId 字段]** → `Session` entity 有 `bound_product_id` 但 MERCHANT_HOME 不需要。Spec 中明确：MERCHANT_HOME 时 boundProductId 必须为 null；PRODUCT_PAGE 时必须非 null 且商品存在。

## Migration Plan

部署顺序：

1. 跑 Flyway V6（生产低峰期）。
2. 部署后端：旧 query `?userId=` 路径已删除，旧前端会因为没有 token 直接 401。
3. 同时切前端：所有 WebSocket 入口改为 `new WebSocket(url, ['Bearer-' + token])`，且建连前调用 `/api/v1/auth/login` + `/api/v1/session/start`。
4. 监控：观察 24 小时内 `Scope cache miss` WARN 日志频率，如果 > 1/min 单独排查。

回滚：
- V6 迁移是单纯扩 CHECK，回滚就是重新 DROP/ADD 缩回旧值，**前提是没有 `MERCHANT_HOME` 行存在**（生产应当还没有，因为代码同步上线）。
- 后端代码回滚需配合前端回滚到 query userId 版本；不允许前后端不一致期间长时间运行。

## Open Questions

- 无。Q1-Q5 的产品决定已写入 Decisions 1, 2, 3, 4, 5。
