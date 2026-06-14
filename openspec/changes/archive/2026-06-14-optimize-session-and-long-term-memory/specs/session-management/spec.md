## MODIFIED Requirements

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

