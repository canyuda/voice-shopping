## ADDED Requirements

### Requirement: Session Entity

The system SHALL define a `Session` JPA Entity mapped to the `session` table with all columns from V1 migration: id (UUID), merchantId, userId, channel, outcome, totalTokens, boundProductId, startedAt, endedAt, createdAt, updatedAt.

`channel` 字段的合法取值集合 MUST 为：`HOME_ENTRY` / `PRODUCT_PAGE` / `SEARCH_FALLBACK`，实体注释 MUST 显式列出该集合。

`outcome` 字段的合法取值集合 MUST 为：`ORDERED` / `ABANDONED` / `FOLLOWUP`，实体注释 MUST 显式列出该集合。null 表示会话尚未结束。

#### Scenario: UUID primary key

- **WHEN** a new Session entity is created without specifying id
- **THEN** JPA delegates id generation to the database default (`gen_random_uuid()`)

#### Scenario: channel 注释列出合法取值

- **WHEN** 阅读 `Session.java` 中 channel 字段的 Javadoc
- **THEN** 注释明确写出 `allowed: HOME_ENTRY / PRODUCT_PAGE / SEARCH_FALLBACK`

#### Scenario: outcome 注释列出合法取值

- **WHEN** 阅读 `Session.java` 中 outcome 字段的 Javadoc
- **THEN** 注释明确写出 `allowed: ORDERED / ABANDONED / FOLLOWUP`，并说明 null 表示未结束

### Requirement: SessionRepository
The system SHALL provide a `SessionRepository` extending `JpaRepository<Session, UUID>` with query method `findByUserIdOrderByStartedAtDesc(userId)`.

#### Scenario: Find sessions by user ID
- **WHEN** `findByUserIdOrderByStartedAtDesc(userId)` is called
- **THEN** all sessions for the given userId are returned, most recent first

### Requirement: SessionService idempotent creation
The system SHALL provide a `SessionService.getOrCreate(sessionId, merchantId, userId, channel)` method that returns the existing session if found, or creates a new one. This guarantees session_state writes always have a parent session record.

#### Scenario: Create new session
- **WHEN** `getOrCreate` is called with a sessionId that does not exist in PG
- **THEN** a new Session row is inserted with the given parameters

#### Scenario: Return existing session (idempotent)
- **WHEN** `getOrCreate` is called with a sessionId that already exists in PG
- **THEN** the existing Session entity is returned without creating a duplicate

### Requirement: SessionState Entity

The system SHALL define a `SessionState` JPA Entity mapped to the `session_state` table with all columns from V1 migration: id (UUID, FK to session), merchantId, phase, currentIntent, slots (JSONB), pendingAsk, turnCount, lastRecommendations (JSONB), createdAt, updatedAt.

`phase` 字段的合法取值集合 MUST 为：`INTENT` / `CLARIFY` / `RECOMMEND` / `ORDER_CONFIRM` / `ENDED`，实体注释 MUST 显式列出该集合。新建实例时 `phase` 字段默认值 MUST 为 `INTENT`（不再是 `IDLE`）。

#### Scenario: JSONB fields mapped correctly

- **WHEN** a SessionState entity is loaded
- **THEN** slots is a Map<String, Object> and lastRecommendations is deserialized as the appropriate type

#### Scenario: phase 字段默认值为 INTENT

- **WHEN** 通过无参构造创建 `new SessionState()`
- **THEN** `getPhase()` 返回 `"INTENT"`

#### Scenario: phase 注释列出合法取值

- **WHEN** 阅读 `SessionState.java` 中 phase 字段的 Javadoc
- **THEN** 注释明确写出 `allowed: INTENT / CLARIFY / RECOMMEND / ORDER_CONFIRM / ENDED`

### Requirement: SessionStateRepository
The system SHALL provide a `SessionStateRepository` extending `JpaRepository<SessionState, UUID>`.

#### Scenario: Find by session ID
- **WHEN** `findById(sessionId)` is called with an existing session state
- **THEN** the SessionState entity is returned

### Requirement: SessionStateService dual-write load
The system SHALL provide a `SessionStateService.load(sessionId)` that reads session state. It SHALL first attempt to read from Redis (`vs:session:{sessionId}`), falling back to PG on cache miss and populating Redis on fallback.

#### Scenario: Cache hit on Redis
- **WHEN** `load(sessionId)` is called and Redis contains the session state
- **THEN** the state is deserialized from Redis and returned without PG access

#### Scenario: Cache miss falls back to PG
- **WHEN** `load(sessionId)` is called and Redis does not contain the session state
- **THEN** the state is loaded from PG, written to Redis, and returned

### Requirement: SessionStateService dual-write save
The system SHALL provide a `SessionStateService.save(sessionState)` that writes to both PG and Redis atomically (PG first, then Redis). save 方法 MUST 标注 `@Transactional(noRollbackFor = org.springframework.data.redis.RedisConnectionFailureException.class)`，确保 Redis 连接失败不会触发 PG 事务回滚。Redis 写入失败（`RedisConnectionFailureException`）MUST 记录 WARN 级别日志，文案统一为 `"Redis 同步失败，下次 load 会从 PG 重建 sessionId={}"`，不向调用方传播异常。

Redis 写入 MUST 使用配置项 `voice-shopping.memory.session-state.ttl`（默认 30m）作为 TTL —— 没有 TTL 的话 SessionExpireListener 永远不会被触发，长期记忆回流就失去 Redis 过期这个被动触发点。TTL 通过 `StringRedisTemplate.opsForValue().set(key, json, ttl)` 三参重载传入。

#### Scenario: Successful dual write with TTL
- **WHEN** `save(sessionState)` is called
- **THEN** the state is persisted to PG and the Redis key is set with the configured TTL (default 30 minutes)

#### Scenario: Redis write failure tolerated
- **WHEN** `save(sessionState)` succeeds on PG but Redis write throws `RedisConnectionFailureException`
- **THEN** the PG write is preserved, WARN 级别日志包含 "Redis 同步失败，下次 load 会从 PG 重建 sessionId=" 与具体 sessionId，no exception is propagated

#### Scenario: noRollbackFor 防止外层事务回滚
- **WHEN** save 在外层 `@Transactional` 方法中被调用，且 Redis 写入抛出 `RedisConnectionFailureException`
- **THEN** PG 事务正常提交（不被 Redis 异常触发回滚），异常被吞并记录日志

#### Scenario: 自定义 TTL 生效
- **WHEN** 启动时配置 `voice-shopping.memory.session-state.ttl=5m`
- **THEN** 写入的 Redis key TTL 为 5 分钟
