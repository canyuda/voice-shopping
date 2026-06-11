## ADDED Requirements

### Requirement: Application configuration template
The `voice-shopping-web` module SHALL contain `src/main/resources/application.yml` with non-sensitive framework configuration and placeholder values for environment-specific settings.

#### Scenario: Application starts with dev profile
- **WHEN** application is launched with `--spring.profiles.active=dev`
- **THEN** it loads `application.yml` merged with `application-dev.yml` overrides

### Requirement: Environment-specific config excluded from VCS
Sensitive configuration files (containing database passwords, API keys) SHALL be excluded from version control via `.gitignore`.

#### Scenario: Sensitive config is not committed
- **WHEN** developer creates `application-dev.yml` with real credentials
- **THEN** `git status` does not show the file as untracked

### Requirement: Root .env file for sensitive values
The project root SHALL contain a `.env` file (gitignored) with placeholder entries: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `REDIS_PASSWORD`, `DASHSCOPE_API_KEY`. The `application-dev.yml` SHALL reference these via Spring's `${ENV_VAR}` syntax.

#### Scenario: .env file is gitignored
- **WHEN** developer creates `.env` with real credentials
- **THEN** `git status` does not show `.env` as untracked

#### Scenario: Application reads config from .env
- **WHEN** application starts with dev profile and `.env` is loaded
- **THEN** datasource url, redis password, and dashscope api key are resolved from environment variables

### Requirement: Gitignore for Java project
The project SHALL contain a `.gitignore` file that excludes build artifacts, IDE files, sensitive configuration, and OS-specific files.

#### Scenario: Build artifacts are not tracked
- **WHEN** developer runs `mvn clean install`
- **THEN** `git status` does not show any `target/` directories as untracked
