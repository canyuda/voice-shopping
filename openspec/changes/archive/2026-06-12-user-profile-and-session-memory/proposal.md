## Why

Orchestrator 和 4 个 Worker Agent 需要用户画像数据和会话上下文来工作，但当前只有数据库表结构（V1 迁移脚本）和 Redis key 规划（`RedisKeys`），没有 Java 实体、Repository、Service 和内存管理层的实现。本版本补齐这些基础设施，使 Agent 层能真正读取画像、维护对话记忆。

## What Changes

- 新增 `user_profile_static` / `user_profile_dynamic` 的 JPA Entity 和 Repository
- 新增 `UserProfileSnapshot` Agent DTO（Record，遵循 agent-dto-specifications.md）
- 新增 `UserProfileService`：合并静态+动态画像为 `UserProfileSnapshot`，`@Cacheable` 缓存到 Redis
- 新增 `ShortTermMemory`：基于 Redis List 的短期对话记忆，支持 append/recent/clear，可配置 TTL 和最大轮数
- 新增 `Session` Entity / Repository / Service：幂等创建会话、按用户 ID 查询
- 新增 `SessionState` Entity / Repository / Service：读写双写（PG + Redis），读优先走 Redis
- 新增 `UserBehaviorSink`：通过 Spring Events 实现行为回流写画像（浏览/购买事件更新动态画像的 category_prefs、brand_prefs 等）
- 新增 `ProfileDebugController`：调试接口，查看用户画像快照和会话短期记忆

## Capabilities

### New Capabilities

- `user-profile-load`: 用户画像加载与缓存——从 PG 读取静态+动态画像，合并为不可变快照，Redis 缓存 24h
- `short-term-memory`: 基于 Redis List 的会话内短期记忆管理——append/recent/clear，可配置 TTL 和最大轮数
- `session-management`: 会话生命周期管理——幂等创建、按用户查询、SessionState 双写读写
- `user-behavior-sink`: 用户行为回流写画像——浏览/购买事件实时更新动态画像偏好数据
- `profile-debug-api`: 调试接口——查看用户画像快照和会话短期记忆内容

### Modified Capabilities

（无）

## Impact

- **新增代码集中在 `voice-shopping-infrastructure`（Entity/Repository）和 `voice-shopping-business`（Service/Memory/Sink）两个模块**
- `voice-shopping-web` 新增 `ProfileDebugController`
- `voice-shopping-common` 新增 `UserProfileSnapshot` Record DTO
- 依赖现有 `RedisKeys` 常量定义，不修改
- 不修改已有 Flyway 迁移脚本
- 不影响现有 Product / FaqEntry / Embedding 相关代码
