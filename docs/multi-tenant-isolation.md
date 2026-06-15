# 多商家数据隔离

本版本（`merchant-data-isolation`）将多租户隔离从"表里有 `merchant_id` 列"扩展为
端到端的不变量：用户身份 → 会话商家范围 → 检索过滤。

## 总览

```
HTTP /api/v1/auth/login        ──► StpUtil.login(uid) ──► token
HTTP /api/v1/session/start     ──► CurrentUser.id() + Channel ──► SessionScope
                                         └─► sessionService.getOrCreate (PG)
                                         └─► scopeCache.put (Redis: vs:scope:<sid>)

WS  /ws/voice                  Sec-WebSocket-Protocol: Bearer-{token}
                               ?sessionId=...
                               ──► AuthHandshakeInterceptor
                                       ├─ token → userId
                                       ├─ sessionId 必填
                                       └─ 回写响应头 Sec-WebSocket-Protocol

ASR sentence-end ──► OrchestratorService ──► (Parallel)RecommendService
                                                   ├─ scopeCache.get(sid)
                                                   │     miss ⇒ platformWide(uid) + WARN
                                                   ├─ scopeFilterBuilder.build
                                                   └─ sqlFilterBuilder.merge(slot, scope)
                                                       ──► productVectorService.search
```

## Channel → SessionScope 解析

| Channel           | 必填字段        | 解析后 scope                                |
|-------------------|----------------|---------------------------------------------|
| `HOME_ENTRY`      | -              | `(userId, [], null)` — 全平台              |
| `SEARCH_FALLBACK` | -              | `(userId, [], null)` — 全平台              |
| `PRODUCT_PAGE`    | `boundProductId` | `(userId, [product.merchantId], productId)` |
| `MERCHANT_HOME`   | `merchantId`   | `(userId, [merchantId], null)`              |

## 不变量

1. **登录依据**：本版本只用 `app_user.phone` 字段（service 层强制全局唯一，多条记录直接 fail-fast；
   不在 DB 上加 unique 约束以保持 V6 迁移最小）。后续真实登录单独立项。
2. **WebSocket 鉴权**：Sub-Protocol token，旧 query `?userId=` 路径已下线。RFC 6455 §4.2.2 要求服务端在 101 响应中
   回写选定的 sub-protocol —— `AuthHandshakeInterceptor` 第 6 步必做。
3. **检索过滤**：所有"拼参数查产品库"的入口（`RecommendOrchestrator` / `ParallelRecommendService` /
   带 `sessionId` 的 `SearchController`）都通过 `ScopeFilterBuilder` + `SqlFilterBuilder.merge` 在生成 SQL filter 时叠加
   `merchant_id IN (...)`。`RecommendCandidateRetriever` 在每次 fallback 重试时重新执行 filterFn，因此放宽分支也不会绕开 scope。
4. **Scope cache miss 降级**：缓存丢失（Redis 故障 / TTL 过期 / 跳过 `/session/start` 直连 WS）时，
   降级为 `(userId, [], null)` 全平台兜底，记 WARN 日志（统一文案 `"Scope cache miss for sessionId={}, falling back to platform-wide"`），
   **不抛异常**。SRE 可以从日志聚合发现频率，超阈值时再做强一致。
5. **不动调试接口**：`RecommendDebugController` / `ChatDebugController` / `EmotionDebugController` 等不强制叠加 scope，
   保留排障时看真实数据形态的能力。

## Backlog（本期不做）

- FAQ 检索（`FaqVectorService`）当前签名是单 `merchantId`，扩成 `List<Long>` 才能配 `SessionScope`，单独立项。
- `belongsToMerchant` 仅供未来商家运营后台使用，目前没有调用点（实现已落，避免后期回头补）。
- 数据库层面 `phone` 唯一约束 / `app_user.login_name` 字段 / `session.allowed_merchant_ids` JSONB 列 — 都暂未做。

## 配置

- Redis key 前缀：`vs:scope:`（与 `vs:session:` / `vs:short_memory:` 同级）。
- TTL：`voice-shopping.memory.session-state.ttl`（默认 30m），与 session-state 共用。
- Flyway V6：扩 `session.channel` CHECK 约束加上 `MERCHANT_HOME`。

## ⚠️ Sa-Token 配置 caveat

`application.yml` 当前配置：

```yaml
sa-token:
  token-name: Authorization
  token-prefix: Bearer
```

行为契约：

- HTTP 请求头：`Authorization: Bearer <token>`
- WebSocket sub-protocol：`Bearer-<token>`（RFC 6455 不允许空格，所以是连字符）
- Redis 登录态 key：`Authorization:login:token:*` / `Authorization:login:session:*`

**`token-name` 在 Sa-Token 设计上同时承担 4 个职责：HTTP 头名 / Cookie 名 / 请求参数名 / Redis key 前缀**
（参见 `cn.dev33.satoken.config.SaTokenConfig#tokenName` 注释：
"token 名称（同时也是： cookie 名称、提交 token 时参数的名称、存储 token 时的 key 前缀）"）。
默认 `SaTokenDaoRedisJackson` 没有独立的 key-prefix 配置项。

**禁止随手改 `token-name`**：

修改 `token-name` 会让所有现存登录态作废 —— Redis key 前缀跟着变，老 token 全部
失联，所有在线用户被强制重新登录。如果将来确实需要换前缀，必须：

1. 业务低峰期操作；
2. 提前批量 RENAME 老 key（`Authorization:* → 新前缀:*`），或者；
3. 直接接受"全员重新登录"的代价并做好通告。

如果想做更彻底的命名空间隔离（比如把 Sa-Token 的 key 跟 `vs:*` 业务 key 分到不同
Redis db），用 `sa-token.alone-redis.*` 配置项指向独立 db；这同样会让现有登录态失效，
属于一次性迁移操作。
