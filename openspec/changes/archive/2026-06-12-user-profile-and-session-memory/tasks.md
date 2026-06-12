## 1. Entity & Repository 层（voice-shopping-infrastructure）

- [x] 1.1 创建 `UserProfileStatic` JPA Entity，映射 `user_profile_static` 表，JSONB `extra` 字段用 `@JdbcTypeCode(SqlTypes.JSON)`
- [x] 1.2 创建 `UserProfileDynamic` JPA Entity，映射 `user_profile_dynamic` 表，JSONB 字段 `category_prefs`/`brand_prefs`/`recent_behavior` 用 `@JdbcTypeCode(SqlTypes.JSON)`
- [x] 1.3 创建 `UserProfileStaticRepository`（extends JpaRepository），提供 `findByUserId(Long userId)` 方法
- [x] 1.4 创建 `UserProfileDynamicRepository`（extends JpaRepository），提供 `findByUserId(Long userId)` 方法
- [x] 1.5 创建 `Session` JPA Entity，映射 `session` 表，id 为 UUID 类型
- [x] 1.6 创建 `SessionRepository`（extends JpaRepository<Session, UUID>），提供 `findByUserIdOrderByStartedAtDesc` 方法
- [x] 1.7 创建 `SessionState` JPA Entity，映射 `session_state` 表，JSONB 字段 `slots`/`lastRecommendations` 用 `@JdbcTypeCode(SqlTypes.JSON)`
- [x] 1.8 创建 `SessionStateRepository`（extends JpaRepository<SessionState, UUID>）

## 2. Agent DTO（voice-shopping-common）

- [x] 2.1 创建 `UserProfileSnapshot` Record（`com.voiceshopping.common.dto.agent`），字段遵循 agent-dto-specifications.md 第 7 节

## 3. Service 层 — 用户画像（voice-shopping-business）

- [x] 3.1 创建 `UserProfileService`，实现 `load(userId)` 方法：查 PG static + dynamic，合并为 UserProfileSnapshot
- [x] 3.2 为 `load(userId)` 添加 `@Cacheable` 注解，缓存到 Redis key `vs:user:profile:{userId}`，TTL 24h
- [x] 3.3 实现 `evictCache(userId)` 方法，用 `@CacheEvict` 删除 Redis 缓存

## 4. Service 层 — 短期记忆（voice-shopping-business）

- [x] 4.1 创建 `ShortTermMemory` 类，定义内部 record `Turn(role, content, turn, agent, timestamp)`，agent 标识产生该轮的 Agent 名称（如 "IntentAgent"），USER 轮次 agent 为 null
- [x] 4.2 实现 `append(sessionId, Turn)`：RPUSH + LTRIM 裁剪到 max-history-turns，首次写入设置 TTL
- [x] 4.3 实现 `recent(sessionId, n)`：LRANGE 返回最近 n 条
- [x] 4.4 实现 `clear(sessionId)`：DELETE Redis key
- [x] 4.5 添加配置项 `voice-shopping.memory.short-term.ttl` 和 `voice-shopping.memory.short-term.max-history-turns`

## 5. Service 层 — Session 管理（voice-shopping-business）

- [x] 5.1 创建 `SessionService`，实现 `getOrCreate(sessionId, merchantId, userId, channel)` 幂等方法
- [x] 5.2 实现 `findByUserId(userId)` 查询方法
- [x] 5.3 创建 `SessionStateService`，实现 `load(sessionId)`：Redis 优先，miss 回填 PG
- [x] 5.4 实现 `save(sessionState)`：PG 先写，Redis 后写（Redis 失败仅记日志不抛异常）

## 6. 行为回流（voice-shopping-business）

- [x] 6.1 创建 `UserBehaviorSink` 类，定义 `@EventListener` 方法 `onViewed` 和 `onPurchased`
- [x] 6.2 实现 `onViewed`：更新 category_prefs/brand_prefs（增量 +viewWeight），追加 recent_behavior
- [x] 6.3 实现 `onPurchased`：更新 category_prefs/brand_prefs（增量 +purchaseWeight），更新 purchase_count/avg_order_amount/last_purchase_at
- [x] 6.4 实现 recent_behavior 裁剪（上限 50 条）
- [x] 6.5 添加配置项 `voice-shopping.behavior.view-weight` 和 `voice-shopping.behavior.purchase-weight`
- [x] 6.6 行为更新后调用 `UserProfileService.evictCache(userId)` 清除画像缓存

## 7. 调试接口（voice-shopping-web）

- [x] 7.1 创建 `ProfileDebugController`，实现 `GET /api/v1/profile/{userId}` 返回 UserProfileSnapshot
- [x] 7.2 实现 `GET /api/v1/profile/memory/{sessionId}` 返回短期记忆列表，支持 `?limit=N` 参数
