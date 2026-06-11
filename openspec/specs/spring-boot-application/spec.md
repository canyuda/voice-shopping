## ADDED Requirements

### Requirement: Spring Boot application entry point
The `voice-shopping-web` module SHALL contain a main class annotated with `@SpringBootApplication` that starts the Spring Boot application on a configurable port.

#### Scenario: Application starts successfully
- **WHEN** developer runs `mvn spring-boot:run` in `voice-shopping-web` module
- **THEN** Spring Boot application starts and listens on the configured port (default 8080)

#### Scenario: Application context loads all modules
- **WHEN** Spring Boot application starts
- **THEN** component scan SHALL cover all submodules' packages under the root package

### Requirement: REST health check endpoint
The application SHALL expose `GET /api/v1/ping` that returns HTTP 200 with a health status response.

#### Scenario: Health check responds
- **WHEN** a GET request is sent to `/api/v1/ping`
- **THEN** the application returns HTTP 200 with JSON body: `{"app":"voice-shopping","status":"ok","ts":<epoch_millis>}`

### Requirement: AgentScope dual model configuration
The `voice-shopping-ai` module SHALL contain an `AgentScopeConfig` class that creates two `DashScopeChatModel` beans: `mainChatModel` (qwen-max, for main dialog) and `lightChatModel` (qwen-turbo, for recommendation/sorting). Both beans SHALL be qualified with `@Bean("mainChatModel")` / `@Bean("lightChatModel")` for injection via `@Qualifier`.

#### Scenario: Two chat models are injectable
- **WHEN** a service class declares `@Qualifier("mainChatModel") DashScopeChatModel mainChatModel` and `@Qualifier("lightChatModel") DashScopeChatModel lightChatModel`
- **THEN** both beans are injected with the correct model configuration

### Requirement: Redis configuration
The `voice-shopping-infrastructure` module SHALL contain a `RedisConfig` class that creates a `RedisTemplate<String, Object>` bean with Jackson-based key/value serialization.

#### Scenario: RedisTemplate is auto-configured with Jackson
- **WHEN** application starts with Redis connection configured
- **THEN** `RedisTemplate<String, Object>` bean is available with JSON serialization

### Requirement: Lombok config for Qualifier
The project root SHALL contain a `lombok.config` file with `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier` to make `@Qualifier` work with Lombok `@RequiredArgsConstructor` constructor injection.

#### Scenario: Qualifier works with constructor injection
- **WHEN** a class uses `@RequiredArgsConstructor` with `@Qualifier` annotated fields
- **THEN** the qualifier annotation is copied to the constructor parameter

### Requirement: Spring Boot Maven plugin in web module
The `voice-shopping-web` module POM SHALL include `spring-boot-maven-plugin` to support packaging as an executable JAR.

#### Scenario: Build produces executable JAR
- **WHEN** developer runs `mvn clean package`
- **THEN** `voice-shopping-web/target/` contains an executable fat JAR
