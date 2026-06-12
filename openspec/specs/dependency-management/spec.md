## ADDED Requirements

### Requirement: Spring Boot BOM inheritance
The parent POM SHALL inherit `spring-boot-dependencies` BOM from Spring Boot 4.0.5 and `spring-ai-alibaba-bom:1.1.2.2` to manage all Spring and Spring AI Alibaba dependency versions.

#### Scenario: Spring dependencies resolve without explicit version
- **WHEN** a submodule declares `spring-boot-starter-web` without `<version>`
- **THEN** the version is resolved from the Spring Boot BOM (4.0.5)

### Requirement: AgentScope dependencies
The project SHALL include `io.agentscope:agentscope-spring-boot-starter:1.0.11` in the `voice-shopping-ai` module.

#### Scenario: AgentScope agent can be built
- **WHEN** `voice-shopping-ai` module is compiled
- **THEN** `ReActAgent`, `Msg`, `DashScopeChatModel` and other AgentScope classes are resolvable

### Requirement: Spring AI Alibaba
The project SHALL include `com.alibaba.cloud.ai:spring-ai-alibaba-starter:1.1.2.0` (managed by BOM `spring-ai-alibaba-bom:1.1.2.2`) in the `voice-shopping-ai` module for Spring AI integration.

#### Scenario: Spring AI Alibaba beans are available
- **WHEN** `voice-shopping-ai` module is compiled
- **THEN** Spring AI Alibaba chat model and embedding model classes are resolvable

### Requirement: DashScope SDK for embedding
The project SHALL include `com.alibaba:dashscope-sdk-java:2.22.4` in the `voice-shopping-ai` module for text-embedding-v3 vectorization.

#### Scenario: DashScope embedding API is available
- **WHEN** `voice-shopping-ai` module is compiled
- **THEN** DashScope SDK classes for text embedding are resolvable

## REMOVED Requirements

### Requirement: Alibaba NLS SDK for ASR and TTS
**Reason**: ASR/TTS 统一使用 DashScope SDK 内置的 `Recognition` + `SpeechSynthesizer`(ttsv2) API，NLS SDK 不再需要。
**Migration**: 从 `voice-shopping-ai/pom.xml` 移除 `nls-sdk-common`、`nls-sdk-transcriber`、`nls-sdk-tts` 三个依赖。所有 ASR/TTS 功能通过 `dashscope-sdk-java` 提供。

## ADDED Requirements

### Requirement: PostgreSQL and pgvector access
The `voice-shopping-infrastructure` module SHALL include `spring-boot-starter-jdbc`, PostgreSQL driver, `com.pgvector:pgvector:0.1.6`, and `io.hypersistence:hypersistence-utils-hibernate-71:3.15.2` for database access with pgvector support.

#### Scenario: JdbcTemplate is auto-configured
- **WHEN** application starts with PostgreSQL connection configured
- **THEN** `JdbcTemplate` bean is available for vector and JSONB queries

### Requirement: Redis integration
The `voice-shopping-infrastructure` module SHALL include `spring-boot-starter-data-redis` for session state and memory cache.

#### Scenario: RedisTemplate is auto-configured
- **WHEN** application starts with Redis connection configured
- **THEN** `RedisTemplate` bean is available for cache operations

### Requirement: Sa-Token for authentication
The `voice-shopping-web` module SHALL include `cn.dev33:sa-token-spring-boot4-starter:1.44.0` and `cn.dev33:sa-token-redis-jackson:1.44.0` for multi-merchant authentication.

#### Scenario: Sa-Token interceptors are registered
- **WHEN** application starts
- **THEN** Sa-Token filter/interceptor is registered in the Spring context
