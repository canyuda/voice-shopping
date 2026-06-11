## ADDED Requirements

### Requirement: Common module package structure
The `voice-shopping-common` module SHALL contain packages: `com.voiceshopping.common.constant`, `com.voiceshopping.common.dto`, `com.voiceshopping.common.util`, `com.voiceshopping.common.enums`.

#### Scenario: Common module provides shared DTOs
- **WHEN** another module depends on `voice-shopping-common`
- **THEN** it can import and use DTOs and utility classes from common packages

### Requirement: Infrastructure module package structure
The `voice-shopping-infrastructure` module SHALL contain packages: `com.voiceshopping.infrastructure.config` (DB/Redis config), `com.voiceshopping.infrastructure.repository` (data access), `com.voiceshopping.infrastructure.vector` (pgvector operations).

#### Scenario: Infrastructure module provides data access beans
- **WHEN** Spring Boot scans the infrastructure module
- **THEN** `@Repository` and `@Configuration` beans from infrastructure packages are registered

### Requirement: AI module package structure
The `voice-shopping-ai` module SHALL contain packages: `com.voiceshopping.ai.agent` (AgentScope agents), `com.voiceshopping.ai.model` (LLM/embedding config), `com.voiceshopping.ai.asr` (ASR streaming), `com.voiceshopping.ai.tts` (TTS streaming), `com.voiceshopping.ai.orchestrator` (state machine).

#### Scenario: AI module provides agent beans
- **WHEN** Spring Boot scans the AI module
- **THEN** Agent and orchestrator `@Service`/`@Component` beans are registered

### Requirement: Business module package structure
The `voice-shopping-business` module SHALL contain packages: `com.voiceshopping.business.product`, `com.voiceshopping.business.order`, `com.voiceshopping.business.user`, `com.voiceshopping.business.recommend`.

#### Scenario: Business module provides service beans
- **WHEN** Spring Boot scans the business module
- **THEN** `@Service` beans from business packages are registered

### Requirement: Web module package structure
The `voice-shopping-web` module SHALL contain packages: `com.voiceshopping.web.controller` (REST), `com.voiceshopping.web.websocket` (WebSocket handlers), `com.voiceshopping.web.config` (Sa-Token, CORS), `com.voiceshopping.web.filter`.

#### Scenario: Web module registers endpoints
- **WHEN** Spring Boot scans the web module
- **THEN** `@RestController` and `@ServerEndpoint` beans are registered as HTTP/WS endpoints
