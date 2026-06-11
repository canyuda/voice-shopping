## 1. Parent POM & Project Root

- [x] 1.1 Create root `pom.xml` with `<packaging>pom</packaging>`, Java 21, Spring Boot 4.0.5 parent, and `<modules>` listing 5 submodules (excluding front)
- [x] 1.2 Declare all third-party version numbers in `<properties>` (AgentScope Starter 1.0.11, Spring AI Alibaba 1.1.2.0/BOM 1.1.2.2, DashScope SDK 2.22.4, pgvector 0.1.6, hypersistence-utils 3.15.2, Sa-Token 1.45.0, okio 3.17.0)
- [x] 1.3 Set up `<dependencyManagement>` inheriting `spring-boot-dependencies` BOM + `spring-ai-alibaba-bom:1.1.2.2` + all third-party dependency versions
- [x] 1.4 Create `.gitignore` (Java/Maven/IDE/sensitive-config/OS files)
- [x] 1.5 Create `lombok.config` at project root with `lombok.copyableAnnotations += org.springframework.beans.factory.annotation.Qualifier`

## 2. Submodule POM Files

- [x] 2.1 Create `voice-shopping-common/pom.xml` (no business dependencies, only utility libs like Jackson, Lombok)
- [x] 2.2 Create `voice-shopping-infrastructure/pom.xml` (depends on common; includes spring-boot-starter-jdbc, postgresql driver, pgvector:0.1.6, hypersistence-utils:3.15.2, mybatis-plus, spring-boot-starter-data-redis)
- [x] 2.3 Create `voice-shopping-ai/pom.xml` (depends on infrastructure; includes agentscope-spring-boot-starter:1.0.11, spring-ai-alibaba-starter-dashscope, dashscope-sdk-java:2.22.4, nls-sdk)
- [x] 2.4 Create `voice-shopping-business/pom.xml` (depends on ai; no additional dependencies beyond transitive)
- [x] 2.5 Create `voice-shopping-web/pom.xml` (depends on business; includes spring-boot-starter-web, spring-boot-starter-websocket, sa-token-spring-boot4-starter:1.45.0, sa-token-redis-jackson:1.45.0, spring-boot-maven-plugin)
- [x] 2.6 Verify `mvn clean install` passes on empty modules

## 3. Package Structure Skeleton

- [x] 3.1 Create `voice-shopping-common` packages: `constant`, `dto`, `util`, `enums`
- [x] 3.2 Create `voice-shopping-infrastructure` packages: `config`, `repository`, `vector`
- [x] 3.3 Create `voice-shopping-ai` packages: `agent`, `model`, `asr`, `tts`, `orchestrator`
- [x] 3.4 Create `voice-shopping-business` packages: `product`, `order`, `user`, `recommend`
- [x] 3.5 Create `voice-shopping-web` packages: `controller`, `websocket`, `config`, `filter`

## 4. Configuration Files

- [x] 4.1 Create `voice-shopping-web/src/main/resources/application.yml` with framework config placeholders (server port, spring.datasource, spring.data.redis, sa-token, dashscope, nls, spring-ai)
- [x] 4.2 Create `voice-shopping-web/src/main/resources/application-dev.yml` referencing env vars for sensitive config
- [x] 4.3 Create `.env` at project root with placeholder entries: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_PASSWORD`, `DASHSCOPE_API_KEY` (gitignored)

## 5. Spring Boot Application & Core Beans

- [x] 5.1 Create `VoiceShoppingApplication.java` main class in `voice-shopping-web` with `@SpringBootApplication`
- [x] 5.2 Create `AgentScopeConfig.java` in `voice-shopping-ai/model` — two `DashScopeChatModel` beans: `mainChatModel` (qwen-max, main dialog) and `lightChatModel` (qwen-turbo, recommendation/sorting), qualified via `@Bean("mainChatModel")` / `@Bean("lightChatModel")`
- [x] 5.3 Create `RedisConfig.java` in `voice-shopping-infrastructure/config` — `RedisTemplate<String, Object>` bean with Jackson serialization
- [x] 5.4 Create `HealthController.java` in `voice-shopping-web/controller` — `GET /api/v1/ping` returns `{"app":"voice-shopping","status":"ok","ts":<epoch_millis>}`

## 6. Build Verification

- [x] 6.1 Run `mvn clean install` from root — all modules compile, tests pass
- [x] 6.2 Run `mvn spring-boot:run -pl voice-shopping-web` — application starts and `GET /api/v1/ping` responds 200
