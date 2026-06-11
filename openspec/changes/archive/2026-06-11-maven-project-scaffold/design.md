## Context

全新项目，目录为空（仅有 `.claude` 和 `CLAUDE.md`）。需要从零搭建 Maven 多模块骨架，承载语音购物助手系统的全部技术栈。

核心约束：
- Java 21 + Spring Boot 4.0.5
- AgentScope Spring Boot Starter 1.0.11
- Spring AI Alibaba 1.1.2.0 (BOM 1.1.2.2)
- DashScope SDK 2.22.4
- pgvector 0.1.6, hypersistence-utils-hibernate-71 3.15.2
- Sa-Token 1.44.0 (spring-boot4-starter + redis-jackson)
- okio 3.17.0
- 5 个子模块，依赖方向严格单向：`web → business → ai → infrastructure → common`

## Goals / Non-Goals

**Goals:**
- 建立完整的 Maven 多模块项目骨架，`mvn clean install` 全模块编译通过
- Parent POM 统一管理所有第三方依赖版本（BOM 模式）
- 各模块声明正确的依赖关系，禁止反向依赖和循环依赖
- 提供可运行的 Spring Boot 启动类
- 提供 `application.yml` 配置模板（占位符形式，不含敏感信息）
- 各模块建立标准 Java 包结构骨架

**Non-Goals:**
- 不实现任何业务逻辑（Agent、ASR、TTS、推荐等留后续 change）
- 不创建数据库表结构或 migration 脚本
- 不实现前端页面，不创建前端模块
- 不配置部署和 CI/CD

## Decisions

### 1. Parent POM 版本管理策略

**选择：Parent POM `<dependencyManagement>` 统一版本 + `<properties>` 声明版本号**

理由：
- Spring Boot 已提供 BOM (`spring-boot-dependencies`)，直接继承即可管理 Spring 全家桶版本
- Spring AI Alibaba 提供 BOM (`spring-ai-alibaba-bom:1.1.2.2`)，管理 Spring AI Alibaba 全家桶版本
- 第三方依赖（AgentScope、DashScope、Sa-Token 等）通过 `<properties>` + `<dependencyManagement>` 统一管控
- 子模块只需声明 `groupId:artifactId`，不带 `<version>`

### 2. Spring Boot 启动模块

**选择：`voice-shopping-web` 作为唯一启动模块**

理由：
- web 层是系统入口，自然承载 `@SpringBootApplication`
- 其他模块作为依赖被引入，不独立启动
- `voice-shopping-web` 的 pom 直接依赖 `voice-shopping-business`，传递引入全部模块

### 3. 数据库访问层

**选择：Spring Boot Starter JDBC + JdbcTemplate**

理由：
- pgvector 的向量操作没有成熟的 JPA/Hibernate 扩展
- JdbcTemplate + 原生 SQL 对 pgvector + JSONB 组合查询最灵活
- MyBatis-Plus 用于常规 CRUD + 租户插件自动注入 `merchant_id`，与 JdbcTemplate 并存不冲突

替代方案：
- Hibernate 6 + 自定义 UserType：映射 vector 类型复杂，社区支持弱
- jOOQ：类型安全但学习成本高，对 pgvector 支持同等问题

### 4. 配置文件结构

**选择：`application.yml` + `application-dev.yml` / `application-prod.yml` profile 分离**

理由：
- 敏感配置（数据库密码、API Key）放在 `.gitignore` 排除的 `application-dev.yml` 中
- `application.yml` 只放非敏感的框架配置和占位符
- 符合 Spring Boot 标准实践

## Risks / Trade-offs

- **Spring Boot 4.0.5 兼容性** → 使用 `sa-token-spring-boot4-starter` 确保 SB4 适配；AgentScope Starter 1.0.11 和 Spring AI Alibaba 1.1.2.0 需验证兼容性。缓解：如启动报错则降级或手动注册 Bean
- **多模块传递依赖冲突** → Spring Boot BOM 能覆盖大部分，但 DashScope SDK 和 NLS SDK 可能有传递依赖冲突。缓解：Parent POM 中对冲突依赖显式 `<exclusion>`
- **MyBatis-Plus 与 JdbcTemplate 并存** → 两者独立使用不冲突，但团队需明确约定：常规 CRUD 用 MyBatis-Plus，向量/JSONB 查询用 JdbcTemplate
