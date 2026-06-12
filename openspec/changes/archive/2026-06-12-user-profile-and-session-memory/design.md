## Context

当前项目已完成数据库表结构设计（V1 迁移脚本包含 `user_profile_static`、`user_profile_dynamic`、`session`、`session_message`、`session_state` 五张表）和 Redis key 规划（`RedisKeys` 常量类），但 Java 层的 Entity、Repository、Service 均未实现。`voice-shopping-business` 模块为空。

已有的代码模式：
- Entity 使用 JPA 标准注解（`@Entity`、`@Table`、`@Column`），JSONB 字段用 Hibernate 6 的 `@JdbcTypeCode(SqlTypes.JSON)` + `@Column(columnDefinition = "jsonb")`
- Repository 继承 `JpaRepository`，放在 `voice-shopping-infrastructure`
- DTO 全部用 Java Record，放在 `voice-shopping-common`
- Redis key 集中在 `RedisKeys` 常量类

## Goals / Non-Goals

**Goals:**
- 实现 UserProfileService：从 PG 加载静态+动态画像，合并为不可变 `UserProfileSnapshot`，Redis 缓存 24h
- 实现 ShortTermMemory：基于 Redis List 的会话内对话记忆，append/recent/clear，可配置 TTL 和最大轮数
- 实现 Session + SessionState 的完整 CRUD，SessionState 读优先 Redis、写双写 PG+Redis
- 实现行为回流写画像（浏览/购买事件 → 实时更新动态画像偏好）
- 提供调试接口查看画像和会话记忆

**Non-Goals:**
- 用户画像录入/编辑接口（本版本不实现）
- Agent 层（IntentAgent/ClarifyAgent/RecAgent/SentimentAgent）的实现
- Orchestrator 状态机编排
- session_message 表的 Entity/Service（本版本不需要读写消息历史，短期记忆走 Redis）
- WebSocket 音频流集成

## Decisions

### D1: 画像缓存策略 — Write-Through + TTL

**决策：** `UserProfileService.load()` 使用 Spring `@Cacheable` 注解，首次加载后写入 Redis，TTL 24h。行为回流（`UserBehaviorSink`）更新 PG 后同步更新 Redis（write-through）。

**理由：** 画像数据读多写少，Agent 每轮对话都需要读。24h TTL 覆盖大部分用户活跃周期，write-through 保证回流后缓存不脏。

**替代方案：** Cache-Aside（读时回填，回流后仅删缓存）。缺点：回流后下一次 Agent 调用会有 PG 查询延迟毛刺。

### D2: 短期记忆用 Redis List

**决策：** `ShortTermMemory` 用 Redis List（`RPUSH` + `LTRIM`），每条记录是一个 JSON 序列化的 `Turn` record。

**理由：** List 的 RPUSH/LRANGE/LTRIM 天然适配 append/recent/clear 语义。max-history-turns 通过 LTRIM 裁剪，不需要应用层判断。TTL 在首次 append 时通过 `EXPIRE` 设置。

### D3: SessionState 双写 — PG 为 source of truth，Redis 为热缓存

**决策：** `SessionStateService.save()` 同时写 PG 和 Redis。`load()` 优先读 Redis，miss 时回填。

**理由：** Orchestrator 每轮对话都频繁读写 session_state，纯 PG 延迟不可接受。PG 保证崩了不丢，Redis 保证热路径快。与 `RedisKeys` 注释"DB source of truth, Redis for hot cache"一致。

### D4: 行为回流用 Spring Events（最小实现）

**决策：** `UserBehaviorSink` 通过 `@EventListener` 监听 Spring ApplicationEvent，同步处理。权重增量（浏览 +0.05，购买 +0.15）做成配置项。

**理由：** 最小实现，不引入 MQ。同步处理保证回流即时生效。后续如需异步/削峰，改为 `@Async` 或替换为 MQ 即可，Event 接口不变。

### D5: Session 创建幂等

**决策：** `SessionService.getOrCreate()` 用 `INSERT ... ON CONFLICT DO NOTHING`（session.id 是 UUID PK）或先查后插。首次调用落库，后续调用返回已有记录。

**理由：** WebSocket 重连场景可能触发多次创建请求。幂等保证 session_state 写入时 session 记录必定存在。

## Risks / Trade-offs

- **[Redis 不可用时画像加载降级]** → `@Cacheable` 的 cacheManager 配置合理的异常处理，Redis miss 直接查 PG。行为回流写 Redis 失败不影响 PG 持久化。
- **[ShortTermMemory 的 LTRIM 与 RPUSH 非原子]** → 实际影响极小，最多多保留 1 条记录。如需严格原子，可用 Lua 脚本，但本版本不必要。
- **[UserBehaviorSink 同步处理阻塞调用方]** → 当前只有浏览/购买两个事件，处理逻辑轻量（读-改-写 JSONB 字段）。如果后续事件量增大，改为 `@Async`。
- **[动态画像 category_prefs/brand_prefs 增量更新的并发安全]** → PG UPDATE 使用 `SET category_prefs = ?` 全量覆盖，并发更新可能覆盖。当前用户量级下单用户并发回流概率极低，本版本可接受。后续可用 `UPDATE ... SET category_prefs = category_prefs || ?` 原子合并。
