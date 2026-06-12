## ADDED Requirements

### Requirement: UserProfileSnapshot DTO
The system SHALL define a `UserProfileSnapshot` Java Record in `com.voiceshopping.common.dto.agent` package, containing all fields specified in `docs/data/agent-dto-specifications.md` section 7 (userId, gender, ageRange, city, bodyHeight, bodyWeight, shoeSize, skinType, techFamiliarity, spendingRange, categoryPrefs, brandPrefs, priceSensitivity, avgOrderAmount, purchaseCount).

#### Scenario: Record creation from static and dynamic entities
- **WHEN** UserProfileService merges a non-null UserProfileStatic and a non-null UserProfileDynamic
- **THEN** the resulting UserProfileSnapshot contains all static fields from UserProfileStatic and all dynamic fields from UserProfileDynamic

#### Scenario: Missing dynamic profile
- **WHEN** UserProfileService loads a user who has a static profile but no dynamic profile row
- **THEN** the resulting UserProfileSnapshot uses sensible defaults for dynamic fields (empty categoryPrefs, empty brandPrefs, priceSensitivity="MEDIUM", avgOrderAmount=null, purchaseCount=0)

### Requirement: UserProfileStatic Entity
The system SHALL define a `UserProfileStatic` JPA Entity mapped to the `user_profile_static` table, with all columns from V1 migration script mapped as fields, including JSONB `extra` field.

#### Scenario: Entity maps all table columns
- **WHEN** a UserProfileStatic entity is loaded by JPA
- **THEN** all columns (id, userId, merchantId, gender, ageRange, city, bodyHeight, bodyWeight, shoeSize, clothingSize, skinType, techFamiliarity, spendingRange, extra, createdAt, updatedAt) are correctly populated

### Requirement: UserProfileDynamic Entity
The system SHALL define a `UserProfileDynamic` JPA Entity mapped to the `user_profile_dynamic` table, with JSONB fields (`categoryPrefs`, `brandPrefs`, `recentBehavior`) properly mapped.

#### Scenario: JSONB fields are correctly deserialized
- **WHEN** a UserProfileDynamic entity is loaded from the database
- **THEN** categoryPrefs is a Map<String, Double>, brandPrefs is a Map<String, Double>, and recentBehavior is a List<Object>

### Requirement: UserProfileRepository
The system SHALL provide `UserProfileStaticRepository` and `UserProfileDynamicRepository` with lookup by `userId`.

#### Scenario: Find static profile by userId
- **WHEN** `findByUserId(userId)` is called with an existing userId
- **THEN** the repository returns an Optional containing the matching UserProfileStatic entity

#### Scenario: Find dynamic profile by userId
- **WHEN** `findByUserId(userId)` is called with an existing userId
- **THEN** the repository returns an Optional containing the matching UserProfileDynamic entity

### Requirement: UserProfileService with Redis caching
The system SHALL provide a `UserProfileService` that loads static and dynamic profiles, merges them into `UserProfileSnapshot`, and caches the result in Redis with 24h TTL using `@Cacheable`.

#### Scenario: First load hits PG and caches result
- **WHEN** `load(userId)` is called for the first time
- **THEN** the service queries PG for both static and dynamic profiles, merges them, stores in Redis under `vs:user:profile:{userId}`, and returns the snapshot

#### Scenario: Subsequent loads hit Redis cache
- **WHEN** `load(userId)` is called and Redis cache exists
- **THEN** the service returns the cached UserProfileSnapshot without querying PG

#### Scenario: Cache eviction on behavior update
- **WHEN** `evictCache(userId)` is called after a behavior sink update
- **THEN** the Redis key `vs:user:profile:{userId}` is removed, forcing next load to query PG
