## ADDED Requirements

### Requirement: GET profile endpoint
The system SHALL expose `GET /api/v1/profile/{userId}` that returns the `UserProfileSnapshot` for the given user, loaded through `UserProfileService.load()` (with cache).

#### Scenario: User profile exists
- **WHEN** GET /api/v1/profile/123 is called and user 123 has profile data
- **THEN** the response is 200 with a JSON body containing the UserProfileSnapshot

#### Scenario: User profile not found
- **WHEN** GET /api/v1/profile/999 is called and user 999 has no profile data
- **THEN** the response is 404

### Requirement: GET session memory endpoint
The system SHALL expose `GET /api/v1/profile/memory/{sessionId}` that returns the recent short-term memory entries for the given session from Redis.

#### Scenario: Session has memory entries
- **WHEN** GET /api/v1/profile/memory/{sessionId} is called and the session has 5 Turns in Redis
- **THEN** the response is 200 with a JSON array of the Turn objects (most recent first, up to max-history-turns)

#### Scenario: Session has no memory entries
- **WHEN** GET /api/v1/profile/memory/{sessionId} is called and no Redis key exists
- **THEN** the response is 200 with an empty JSON array

#### Scenario: Optional query parameter for limit
- **WHEN** GET /api/v1/profile/memory/{sessionId}?limit=3 is called
- **THEN** only the 3 most recent Turns are returned
