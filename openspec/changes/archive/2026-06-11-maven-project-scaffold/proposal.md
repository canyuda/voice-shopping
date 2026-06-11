## Why

项目从零搭建，需要一个完整的 Maven 多模块项目骨架来承载语音购物助手系统。骨架必须覆盖全部 5 个业务模块（common / infrastructure / ai / business / web），并集成 Spring Boot 4.0.5、AgentScope Java、DashScope SDK、阿里云 NLS SDK、pgvector、Redis、Sa-Token 等所有技术栈依赖。项目骨架是所有后续功能开发的基础前置条件。

## What Changes

- 创建 Maven 多模块 parent POM，统一管理 Java 21、Spring Boot 4.0.5、AgentScope Starter 1.0.11、Spring AI Alibaba 1.1.2.0 等版本
- 创建 5 个子模块：`voice-shopping-common`、`voice-shopping-infrastructure`、`voice-shopping-ai`、`voice-shopping-business`、`voice-shopping-web`
- 各模块 `pom.xml` 声明正确的依赖关系，确保单向依赖：`web → business → ai → infrastructure → common`
- `voice-shopping-web` 作为启动模块，包含 Spring Boot Application 主类
- 各模块创建标准包结构（controller / service / repository / config 等）
- 创建 `application.yml` + `application-dev.yml` 配置文件模板
- 创建 `AgentScopeConfig` 配置类（mainChatModel qwen-max + lightChatModel qwen-turbo）
- 创建 `RedisConfig` 配置类（RedisTemplate + Jackson 序列化）
- 创建 `HealthController`（`GET /api/v1/ping` 健康检查）
- 创建 `lombok.config`（启用 @Qualifier 构造器注入复制）
- 创建 `.gitignore`（Java + Maven + IDE + 敏感配置文件排除）

## Capabilities

### New Capabilities
- `maven-multimodule-layout`: Maven 多模块项目结构，parent POM 统一版本管理，子模块依赖方向约束
- `spring-boot-application`: Spring Boot 4.0.5 启动模块，REST + WebSocket 自动配置，Application 主类
- `dependency-management`: 全部技术栈依赖声明（AgentScope 1.0.11、Spring AI Alibaba 1.1.2.0、DashScope SDK 2.22.4、pgvector 0.1.6、Sa-Token 1.44.0、hypersistence-utils 3.15.2、okio 3.17.0 等）
- `project-config`: 配置文件模板（application.yml 多环境）
- `module-package-structure`: 各模块标准 Java 包结构骨架

### Modified Capabilities

（无，全新项目）

## Impact

- **代码**：从空目录到完整项目骨架，约 15-20 个 POM/配置文件 + 各模块包结构
- **依赖**：首次引入 Spring Boot 4.0.5、AgentScope Starter 1.0.11、Spring AI Alibaba 1.1.2.0、DashScope SDK 2.22.4、pgvector 0.1.6、Sa-Token 1.44.0 等全部依赖
- **构建**：`mvn clean install` 应能全模块编译通过
