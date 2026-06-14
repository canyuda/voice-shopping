# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## 项目概述

语音购物助手 — 基于 AgentScope Java 的多智能体语音购物系统。用户通过语音与 AI 助手交互，完成商品搜索、推荐、下单等操作。全链路流式处理：ASR → Agent → TTS，WebSocket 全双工通信。

## 技术栈

- **Java 21** + **Spring Boot 4.0.5** + **Maven 多模块**
- **AgentScope Java 1.0.11** — 多智能体编排（ReActAgent + 手写 Orchestrator 状态机）
- **qwen-max / qwen-turbo** — 主对话 + 推荐排序，AgentScope `DashScopeChatModel` 接入
- **text-embedding-v3** — DashScope SDK 直接调用，1024 维向量
- **阿里云 NLS SDK** — ASR（Paraformer 实时版）+ TTS（CosyVoice）
- **PostgreSQL + pgvector** — 关系数据 + HNSW 向量检索 + JSONB 属性过滤
- **Redis 7.x** — 对话状态、短期记忆、画像缓存
- **Sa-Token 1.45.0** — 多商家认证 + 行级数据隔离（`merchant_id` 字段）
- **Spring AI Alibaba 1.1.2.0** — 已通过 `spring.autoconfigure.exclude` 排除自动配置，仅用 BOM 管理版本

## 模块结构

```
voice-shopping/
├── voice-shopping-common            # 公共工具、常量、DTO、异常
├── voice-shopping-infrastructure    # Entity、Repository、向量检索、Redis 配置
├── voice-shopping-ai                # AgentScope 配置、Agent 实现（规划中）
├── voice-shopping-business          # Service 层（画像、会话、记忆、行为回流）
└── voice-shopping-web               # Controller、WebSocket、Sa-Token
```

依赖方向：`web → business → ai → infrastructure → common`

## 编码规范

- 交互用中文，代码和注释用英文，文档用中文
- 优先使用函数式编程范式
- 简洁优于巧妙，可读性第一
- 错误处理遵循 fail fast 原则，严禁吞掉异常
- 禁止硬编码（中间件配置、第三方接口地址等走配置文件）
- 接口请求体和响应体禁止使用 `Map<String, Object>`，必须定义专用类（Record 或 POJO）
- Agent DTO 一律用 Java Record，定义在 `com.voiceshopping.common.dto.agent`，详见 `docs/data/agent-dto-specifications.md`

### 统一响应格式

所有接口返回 `ApiResult<T>`（code/msg/data），不使用 `ResponseEntity` 包裹：

```java
// 成功
return ApiResult.ok(data);

// 失败 — 抛异常，禁止在 Controller 手动构建错误响应
throw new NotFoundException("资源不存在");
throw new ForbiddenException("权限不足");
throw new BusinessException(400, "参数错误");
```

异常由 `GlobalExceptionHandler`（`@RestControllerAdvice`）统一处理。

### 已有异常类

| 异常类 | HTTP 状态码 | 用途 |
|--------|------------|------|
| `NotFoundException` | 404 | 资源不存在 |
| `ForbiddenException` | 403 | 权限不足 |
| `BusinessException` | 自定义 statusCode | 通用业务异常 |
| `IllegalArgumentException` | 400 | 参数校验失败 |

### Flyway 迁移

已执行的迁移脚本（`db/migration/V{n}__*.sql`）禁止修改。如需变更表结构，新建下一个版本号的迁移脚本。修改前必须确认该脚本是否已执行。

## Agent 架构

4 个 Worker Agent + 1 个手写 Orchestrator 状态机，详见 `docs/data/agent-dto-specifications.md`。

```
Orchestrator 状态机: INTENT → CLARIFY → RECOMMEND → ORDER_CONFIRM → ENDED
```

意图分支路由：
- `PRODUCT_RECOMMENDATION` → ClarifyAgent → RecAgent → EmotionAgent
- `PRODUCT_COMPARE` → RecAgent → EmotionAgent
- `CLARIFY_NEEDED` → ClarifyAgent（循环直到 READY）
- `ORDER_CONFIRM` → 订单流程
- `CHITCHAT` → EmotionAgent 直接应答
- `OUT_OF_SCOPE` → EmotionAgent 礼貌拒绝

## Redis Key 规范

所有 key 以 `vs:` 为前缀，定义在 `RedisKeys` 常量类：

| Key | 类型 | TTL | 用途 |
|-----|------|-----|------|
| `vs:user:profile:{userId}` | Cache | 24h | 用户画像快照缓存 |
| `vs:session:{sessionId}` | String | 30min | 会话状态热缓存 |
| `vs:short_memory:{sessionId}` | List | 30min | 会话内短期对话记忆 |
| `vs:intent_cache:{userId}:{hash}` | String | 5min | 意图识别缓存 |
| `vs:rec_cache:{hash}` | String | 10min | 推荐结果缓存 |

## 数据库表

PG 主库，向量字段用 pgvector `vector(1024)`，灵活属性用 JSONB。完整表结构见 `docs/data/table-specifications.md`。

核心表：`merchant` / `app_user` / `product` / `faq_entry` / `user_profile_static` / `user_profile_dynamic` / `session` / `session_message` / `session_state` / `order_record`

## 已实现功能

- ✅ 商品向量检索（pgvector HNSW + JSONB 过滤）
- ✅ FAQ 语义问答（相似度阈值 0.75）
- ✅ 商品 / FAQ 批量向量写入（DashScope embedding + 虚拟线程并发）
- ✅ 用户画像加载与 Redis 缓存（静态 + 动态合并为 UserProfileSnapshot）
- ✅ 会话内短期记忆（Redis List，可配置 TTL 和最大轮数）
- ✅ 会话管理（幂等创建、SessionState PG+Redis 双写）
- ✅ 行为回流写画像（Spring Events，浏览/购买事件更新动态偏好）
- ✅ 调试接口（画像快照查询 + 会话记忆查询）
- ✅ 统一响应格式 + 全局异常处理
