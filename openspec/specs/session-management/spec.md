## ADDED Requirements

### Requirement: Session Entity
The system SHALL define a `Session` JPA Entity mapped to the `session` table with all columns from V1 migration: id (UUID), merchantId, userId, channel, outcome, totalTokens, boundProductId, startedAt, endedAt, createdAt, updatedAt.

#### Scenario: UUID primary key
- **WHEN** a new Session entity is created without specifying id
- **THEN** JPA delegates id generation to the database default (`gen_random_uuid()`)

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

#### Scenario: JSONB fields mapped correctly
- **WHEN** a SessionState entity is loaded
- **THEN** slots is a Map<String, Object> and lastRecommendations is deserialized as the appropriate type

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
The system SHALL provide a `SessionStateService.save(sessionState)` that writes to both PG and Redis atomically (PG first, then Redis). If Redis write fails, PG write is not rolled back.

#### Scenario: Successful dual write
- **WHEN** `save(sessionState)` is called
- **THEN** the state is persisted to PG and the Redis key is updated

#### Scenario: Redis write failure tolerated
- **WHEN** `save(sessionState)` succeeds on PG but Redis write fails
- **THEN** the PG write is preserved, the error is logged, and no exception is propagated
