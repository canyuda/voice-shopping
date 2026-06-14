## MODIFIED Requirements

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
