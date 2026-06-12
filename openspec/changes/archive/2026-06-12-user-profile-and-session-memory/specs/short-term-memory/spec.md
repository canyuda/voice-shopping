## ADDED Requirements

### Requirement: Turn record
The system SHALL define an inner `Turn` record in `ShortTermMemory` with fields: `role` (String, "USER"/"ASSISTANT"/"SYSTEM"), `content` (String), `turn` (int), `agent` (String, nullable — identifies which agent produced this turn, e.g. "IntentAgent", "RecAgent"; null for USER turns), `timestamp` (Instant).

#### Scenario: Turn serialization
- **WHEN** a Turn is serialized to JSON and stored in Redis
- **THEN** it can be deserialized back to an identical Turn instance

### Requirement: ShortTermMemory append
The system SHALL provide an `append(sessionId, Turn)` method that appends a Turn to the Redis List `vs:short_memory:{sessionId}` and trims the list to `max-history-turns` entries. On first append, the Redis key SHALL be assigned the configured TTL.

#### Scenario: Append to new session
- **WHEN** `append(sessionId, turn)` is called for a session with no existing memory
- **THEN** a Redis List is created with one entry, TTL is set, and list length is 1

#### Scenario: Append and trim at max capacity
- **WHEN** `append(sessionId, turn)` is called and the list already has `max-history-turns` entries
- **THEN** the oldest entry is removed, the new entry is appended, and list length remains `max-history-turns`

#### Scenario: TTL set on first append only
- **WHEN** `append(sessionId, turn)` is called on a brand-new Redis key
- **THEN** the TTL is set to the configured value (default 30 minutes)

### Requirement: ShortTermMemory recent
The system SHALL provide a `recent(sessionId, n)` method that returns the most recent `n` Turns from the Redis List, ordered from oldest to newest.

#### Scenario: Request fewer turns than stored
- **WHEN** `recent(sessionId, 3)` is called and the list has 10 entries
- **THEN** the 3 most recent Turns are returned (LRANGE with negative index)

#### Scenario: Request more turns than stored
- **WHEN** `recent(sessionId, 10)` is called and the list has 3 entries
- **THEN** all 3 Turns are returned

#### Scenario: No memory exists for session
- **WHEN** `recent(sessionId, 5)` is called and no Redis key exists
- **THEN** an empty list is returned

#### Scenario: IntentAgent reads recent 3 turns for context
- **WHEN** IntentAgent calls `recent(sessionId, 3)` before processing a new user utterance
- **THEN** it receives the last 3 Turns (regardless of role/agent) to use as conversation context

#### Scenario: SentimentAgent reads recent 2 turns for mood trend
- **WHEN** SentimentAgent calls `recent(sessionId, 2)` before generating an emotional response
- **THEN** it receives the last 2 Turns to assess user mood trajectory

#### Scenario: Orchestrator appends each turn result
- **WHEN** the Orchestrator completes processing a user turn (ASR result, Agent output, etc.)
- **THEN** it calls `append(sessionId, turn)` with the appropriate role, agent name, and content

### Requirement: ShortTermMemory clear
The system SHALL provide a `clear(sessionId)` method that deletes the Redis List `vs:short_memory:{sessionId}`.

#### Scenario: Clear existing memory
- **WHEN** `clear(sessionId)` is called on a session with existing memory entries
- **THEN** the Redis key is deleted and subsequent `recent()` calls return empty list

### Requirement: Configurable TTL and max-history-turns
The system SHALL accept configuration properties for `voice-shopping.memory.short-term.ttl` (default 30m) and `voice-shopping.memory.short-term.max-history-turns` (default 20).

#### Scenario: Custom configuration values
- **WHEN** the application is started with `voice-shopping.memory.short-term.ttl=60m` and `voice-shopping.memory.short-term.max-history-turns=50`
- **THEN** ShortTermMemory uses 60 minutes TTL and trims to 50 entries
