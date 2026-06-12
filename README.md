# Voice Shopping — 语音购物助手

基于多智能体（Multi-Agent）架构的语音购物系统。用户通过语音与 AI 助手自然交互，系统自动完成意图理解、需求澄清、商品推荐、情感应答，全链路流式处理：ASR → Agent → TTS，WebSocket 全双工通信。

## 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 语言 | Java 21 | 虚拟线程支持 |
| 框架 | Spring Boot 4.0.5 | REST + WebSocket |
| Agent | AgentScope Java 1.0.11 | 多智能体编排（Project Reactor 响应式） |
| 大模型 | qwen-max / qwen-turbo | 主对话 + 推荐排序 |
| 向量化 | text-embedding-v3 (DashScope) | 1024 维语义向量 |
| 语音 ASR | Paraformer 实时版（阿里云 NLS） | 流式语音识别 |
| 语音 TTS | CosyVoice（阿里云 NLS） | 流式语音合成，情感可控 |
| 数据库 | PostgreSQL + pgvector | 关系数据 + HNSW 向量检索 |
| 缓存 | Redis 7.x | 对话状态、短期记忆 |
| 权限 | Sa-Token 1.45.0 | 多商家认证 + 行级数据隔离 |
| 前端 | H5 SPA | Web Audio API 录音/播放 + 推荐卡片 |

## Agent 架构

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
├── voice-shopping-common          # 公共工具、常量、DTO
├── voice-shopping-infrastructure  # 基础设施层（PG、Redis、向量检索）
├── voice-shopping-ai              # AI 能力层（AgentScope、DashScope、NLS）
├── voice-shopping-business        # 业务服务层（商品、订单、用户画像）
└── voice-shopping-web             # 通信接口层（REST、WebSocket、Sa-Token）
```

依赖方向：`web → business → ai → infrastructure → common`

## 核心特性

### 向量检索

JdbcTemplate + pgvector 原生 SQL，HNSW 索引余弦相似度检索，SQL 层完成向量检索 + JSONB 属性过滤的组合查询。商品和 FAQ 均支持语义搜索。

### FAQ 问答

高频问题命中 `faq_entry` 表直接返回预写答案，不走 LLM 对话链路，节省 token。向量相似度阈值 ≥ 0.75 命中，低于阈值走 LLM 兜底。

### 多商家隔离

每张业务表含 `merchant_id` 字段，通过 MyBatis-Plus 租户插件自动注入 WHERE 条件，实现行级数据隔离。

### 全双工音频流

WebSocket 二进制帧传 PCM 音频，文本帧传 JSON 控制消息，上行录音 + 下行播放同时进行。

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- PostgreSQL 15+ (需安装 pgvector 扩展)
- Redis 7.x

### 配置

在 `application.yml` 中配置以下环境变量（或直接填写）：

| 变量 | 说明 |
|------|------|
| `DASHSCOPE_API_KEY` | DashScope API 密钥 |
| `SPRING_DATASOURCE_URL` | PostgreSQL 连接地址 |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户名 |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 |
| `SPRING_DATA_REDIS_HOST` | Redis 地址 |

### 构建与运行

```bash
mvn clean package -DskipTests
java -jar voice-shopping-web/target/voice-shopping-web.jar
```

### 数据库迁移

Flyway 自动执行，启动时自动运行 `db/migration/` 下的迁移脚本。

## API 概览

| 接口 | 方法 | 说明 |
|------|------|------|
| `/api/v1/faq/ask` | POST | FAQ 语义问答（merchantId 可选） |
| `/api/v1/faq/ask-debug` | POST | FAQ 调试接口（返回 top-N 候选） |
| `/api/v1/search/product` | POST | 商品向量搜索 |
| `/api/v1/reindex/product` | POST | 商品批量向量写入 |
| `/api/v1/reindex/faq` | POST | FAQ 批量向量写入 |

## 文档

- [系统架构文档](docs/architecture.md)
- [表字段规格](docs/data/table-specifications.md)
- [集成测试用例](docs/data/test-cases.md)

## License

Private — All rights reserved.
