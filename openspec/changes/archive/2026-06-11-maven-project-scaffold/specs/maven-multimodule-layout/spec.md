## ADDED Requirements

### Requirement: Parent POM with version management
The parent POM at project root SHALL declare `<packaging>pom</packaging>` and list all 6 submodules in `<modules>`. It SHALL use `<properties>` for all version numbers and `<dependencyManagement>` for unified dependency version control, inheriting `spring-boot-dependencies` BOM.

#### Scenario: Maven reactor builds all modules
- **WHEN** developer runs `mvn clean install` from project root
- **THEN** Maven reactor resolves all 6 submodules and builds them in dependency order

#### Scenario: Version number is centralized
- **WHEN** a submodule declares a dependency without `<version>`
- **THEN** the version is resolved from parent POM's `<dependencyManagement>`

### Requirement: Unidirectional module dependencies
Submodule dependencies SHALL follow the strict direction: `web → business → ai → infrastructure → common`. No reverse dependency or circular dependency SHALL exist.

#### Scenario: Web module depends on business module
- **WHEN** `voice-shopping-web` declares a dependency on `voice-shopping-business`
- **THEN** Maven build succeeds and all transitive dependencies resolve correctly

#### Scenario: Reverse dependency is rejected
- **WHEN** `voice-shopping-common` accidentally depends on `voice-shopping-web`
- **THEN** Maven build SHALL fail with a circular dependency error

### Requirement: Each submodule has its own POM
Each of the 6 submodules SHALL have its own `pom.xml` with `<parent>` pointing to the root parent POM. Each POM SHALL only declare dependencies relevant to its module's responsibility.

#### Scenario: Module POM inherits parent version
- **WHEN** a submodule POM declares `<parent>` as the root POM
- **THEN** the submodule inherits all version management and plugin configurations
