# Voice Shopping — 语音购物助手

基于多智能体（Multi-Agent）架构的语音购物系统。用户通过语音与 AI 助手自然交互，系统自动完成意图理解、需求澄清、商品推荐、情感应答，全链路流式处理：ASR → Agent → TTS，WebSocket 全双工通信。

## 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 语言 | Java 21 | 虚拟线程支持 |
| 框架 | Spring Boot 4.0.5 | REST + WebSocket |
| Agent | AgentScope Java 1.0.11 | 多智能体编排（ReActAgent + 手写 Orchestrator） |
| 大模型 | qwen-max / qwen-turbo | 主对话 + 推荐排序（AgentScope DashScopeChatModel） |
| 向量化 | text-embedding-v3 (DashScope) | 1024 维语义向量 |
| 语音 ASR | Paraformer 实时版（阿里云 NLS） | 流式语音识别 |
| 语音 TTS | CosyVoice（阿里云 NLS） | 流式语音合成，情感可控 |
| 数据库 | PostgreSQL + pgvector | 关系数据 + HNSW 向量检索 |
| 缓存 | Redis 7.x | 对话状态、短期记忆、画像缓存 |
| 权限 | Sa-Token 1.45.0 | 多商家认证 + 行级数据隔离 |
| 前端 | H5 SPA | Web Audio API 录音/播放 + 推荐卡片 |

## 架构概览

```
┌─────────────────────────────────────────────────┐
│                  Orchestrator                     │
│            (手写 Service 级状态机)                 │
│                                                   │
│  IDLE → INTENT_PARSED → CLARIFYING →              │
│  READY_TO_RECOMMEND → GENERATING_SPEECH → IDLE   │
└──────┬──────────┬──────────┬──────────┬──────────┘
       │          │          │          │
  IntentAgent  ClarifyAgent RecAgent  SentimentAgent
  (意图理解)   (需求澄清)   (商品推荐) (情感应答)
```

**数据流：**

```
用户语音 → WebSocket → ASR 转文本 → Orchestrator → Agent 链路
→ 数据库/向量库检索 → 情感应答包装 → TTS 流式合成 → WebSocket → 用户
```

根据意图类型分支路由：

| 意图 | 链路 |
|------|------|
| PRODUCT_RECOMMENDATION | ClarifyAgent → RecAgent → SentimentAgent |
| PRODUCT_COMPARE | RecAgent → SentimentAgent |
| CLARIFY_NEEDED | ClarifyAgent（循环直到 READY） |
| ORDER_CONFIRM | 直接走订单流程 |
| CHITCHAT | SentimentAgent 直接应答 |
| OUT_OF_SCOPE | SentimentAgent 礼貌拒绝 |

## 模块结构

```
voice-shopping/
├── voice-shopping-common            # 公共工具、常量、DTO、异常类
├── voice-shopping-infrastructure    # Entity、Repository、向量检索、Redis 配置
├── voice-shopping-ai                # AgentScope 配置、Agent 实现
├── voice-shopping-business          # Service 层（画像、会话、记忆、行为回流）
└── voice-shopping-web               # Controller、WebSocket、Sa-Token
```

依赖方向：`web → business → ai → infrastructure → common`

## 核心特性

### 向量检索

JdbcTemplate + pgvector 原生 SQL，HNSW 索引余弦相似度检索，SQL 层完成向量检索 + JSONB 属性过滤的组合查询。商品和 FAQ 均支持语义搜索。

### FAQ 问答

高频问题命中 `faq_entry` 表直接返回预写答案，不走 LLM 对话链路，节省 token。向量相似度阈值 ≥ 0.75 命中，低于阈值走 LLM 兜底。

### 用户画像

静态画像（`user_profile_static`）+ 动态画像（`user_profile_dynamic`）合并为不可变 `UserProfileSnapshot`，供所有 Agent 做个性化推荐。Redis 缓存 24h，行为回流（浏览/购买事件）实时更新动态偏好并自动刷新缓存。

### 会话记忆

三层记忆架构：
- **短期记忆**（Redis List，30min TTL）— 最近 N 轮对话，供 IntentAgent（3 轮上下文）和 SentimentAgent（2 轮情绪趋势）实时读取
- **会话状态**（PG + Redis 双写）— Orchestrator 状态机，读优先 Redis，写同时写 PG 和 Redis
- **长期记忆**（PG user_profile）— 跨会话的用户画像和历史行为

### 多商家隔离

每张业务表含 `merchant_id` 字段，通过 MyBatis-Plus 租户插件自动注入 WHERE 条件，实现行级数据隔离。

### 全双工音频流

WebSocket 二进制帧传 PCM 音频，文本帧传 JSON 控制消息，上行录音 + 下行播放同时进行。

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- PostgreSQL 15+（需安装 pgvector 扩展）
- Redis 7.x

### 配置

在 `application.yml` 中配置以下环境变量（或直接填写）：

| 变量 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | DashScope API 密钥 |
| `DB_URL` | PostgreSQL 连接地址 |
| `DB_USER` / `DB_PASSWORD` | 数据库用户名 / 密码 |
| `REDIS_HOST` / `REDIS_PASSWORD` | Redis 地址 / 密码 |

### 构建与运行

```bash
mvn clean package -DskipTests
java -jar voice-shopping-web/target/voice-shopping-web.jar
```

### 数据库迁移

Flyway 自动执行，启动时自动运行 `db/migration/` 下的迁移脚本。

## API 概览

### 商品与 FAQ

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/v1/search?q=跑鞋` | GET | 商品向量搜索 |
| `/api/v1/faq/ask` | POST | FAQ 语义问答 |
| `/api/v1/faq/ask-debug` | POST | FAQ 调试接口（返回 top-N 候选） |

### 用户画像与调试

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/v1/profile/{userId}` | GET | 查看用户画像快照 |
| `/api/v1/profile/memory/{sessionId}` | GET | 查看会话短期记忆（`?limit=N`） |

### 管理接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/v1/admin/reindex` | POST | 商品批量向量重写 |
| `/api/v1/admin/faq-reindex` | POST | FAQ 批量向量重写 |
| `/api/v1/ping` | GET | 健康检查 |

所有接口返回统一格式：`{ "code": 200, "msg": "success", "data": {...} }`

## 文档

- [表字段规格](docs/data/table-specifications.md)
- [Agent DTO 契约规格](docs/data/agent-dto-specifications.md)
- [ER 图](docs/data/er-diagram.md)
- [测试用例](docs/data/test-cases.md)
- [测试数据（商品）](docs/data/test-data-product.sql)
- [测试数据（用户画像）](docs/data/test-data-user-profile.sql)
- [测试数据（短期记忆 Redis）](docs/data/test-data-short-memory.redis)

## License

Private — All rights reserved.
