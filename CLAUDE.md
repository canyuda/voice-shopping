# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

语音购物助手 — 基于 AgentScope Java 的多智能体语音购物系统。用户通过语音与 AI 助手交互，完成商品搜索、推荐、下单等操作。全链路流式处理：ASR → Agent → TTS，WebSocket 全双工通信。

## 技术栈

### 后端框架
- **Java 21** — 虚拟线程支持
- **Spring Boot 4.0.5** — REST + WebSocket
- **Maven** — 多模块项目管理
- **okio 3.17.0** (`com.squareup.okio:okio`) — I/O 库，NLS SDK / DashScope 传递依赖版本统一管理

### AI / Agent
- **AgentScope Spring Boot Starter 1.0.11** (`io.agentscope:agentscope-spring-boot-starter`) — 多智能体编排，响应式架构（Project Reactor）
- **Spring AI Alibaba 1.1.2.0** (`com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`) — Spring AI 阿里云集成，BOM `spring-ai-alibaba-bom:1.1.2.2`（自动配置与 SB4 暂不兼容，已通过 `spring.autoconfigure.exclude` 排除）
- **DashScope SDK 2.22.4** (`com.alibaba:dashscope-sdk-java`) — 向量化模型 text-embedding-v3 接入
- AgentScope 内置 `DashScopeChatModel` 直接对接 qwen 系列，无需额外 SDK 做对话

### 大模型
| 用途 | 模型 | 接入方式 |
|------|------|----------|
| 主对话 | qwen-max | AgentScope `DashScopeChatModel` |
| 推荐/排序/重排 | qwen-turbo | AgentScope `DashScopeChatModel` |
| 向量化 | text-embedding-v3 | DashScope SDK 直接调用 |

### 语音
- **ASR** — 阿里云 Paraformer 实时版，流式语音识别，通过阿里云 NLS SDK 接入
- **TTS** — 阿里云 CosyVoice，流式语音合成，情感可控，通过阿里云 NLS SDK 接入

### 数据存储
- **PostgreSQL + pgvector 0.1.6** (`com.pgvector:pgvector`) — 关系数据（商品、商家、用户画像、订单）+ 向量检索（HNSW 索引 + JSONB 属性过滤）
- **hypersistence-utils-hibernate-71 3.15.2** (`io.hypersistence:hypersistence-utils-hibernate-71`) — Hibernate 增强工具
- **Redis 7.x** — 对话状态、短期记忆缓存

### 权限
- **Sa-Token 1.45.0** (`cn.dev33:sa-token-spring-boot4-starter` + `sa-token-redis-jackson`) — 多商家认证 + 行级数据隔离（`merchant_id` 字段 + SQL WHERE 过滤）

### 前端
- **H5 单页应用** — Web Audio API (AudioWorklet) 麦克风录音、PCM 音频流播放、推荐卡片展示

## Agent 架构

### 总体设计

4 个 Worker Agent（均为 AgentScope `ReActAgent`）+ 1 个手写 Orchestrator 状态机（Service 层）。

Orchestrator 不走 AgentScope 的 pipeline，而是手写一个 Service 级状态机，显式调用、显式分支各 Worker Agent。各层职责严格隔离：WebSocket 只管音频帧路由，ASR/TTS 只管语音和文本转换，Agent 只管业务决策，存储层只管数据。各层之间严格通过 `Msg` 对象通信，不越界。

### 数据流

```
用户语音 → WebSocket 上行 → ASR 转文本 → Orchestrator 接手 → MsgHub 派发给对应 Agent
→ Agent 读数据库/向量库 → 结果回 Orchestrator → 情感 Agent 包装 → TTS 流式合成
→ WebSocket 下行 → 用户耳朵
```

### Orchestrator 状态机

手写 Service 级状态机，核心状态流转：

```
IDLE → INTENT_PARSED → CLARIFYING → READY_TO_RECOMMEND → GENERATING_SPEECH → IDLE
```

根据 IntentAgent 返回的意图类型决定分支：
- `PRODUCT_RECOMMENDATION` → ClarifyAgent → RecAgent → SentimentAgent
- `PRODUCT_COMPARE` → RecAgent → SentimentAgent
- `CLARIFY_NEEDED` → ClarifyAgent（循环直到 READY）
- `ORDER_CONFIRM` → 直接走订单流程
- `CHITCHAT` → SentimentAgent 直接应答
- `OUT_OF_SCOPE` → SentimentAgent 礼貌拒绝

### Worker Agent 契约

#### 1. IntentAgent（意图理解）

识别用户意图 + 抽取槽位。

**输入：**
```json
{
  "userId": "u_123",
  "sessionId": "sess_abc",
  "utterance": "我想买双跑鞋，预算 500 以内",
  "recentHistory": ["最近 3 轮对话摘要"]
}
```

**输出：**
```json
{
  "intent": "PRODUCT_RECOMMENDATION",
  "slots": { "category": "跑鞋", "budget": 500, "scenario": null, "brand": null },
  "confidence": 0.91
}
```

**意图枚举：** `PRODUCT_RECOMMENDATION` / `CLARIFY_NEEDED` / `PRODUCT_COMPARE` / `CHITCHAT` / `ORDER_CONFIRM` / `OUT_OF_SCOPE`

#### 2. ClarifyAgent（需求澄清）

根据品类规则判断是否需要追问缺失槽位。规则优先，LLM 兜底。

**输入：**
```json
{
  "slots": { "category": "跑鞋", "budget": 500, "scenario": null },
  "category": "跑鞋"
}
```

**输出：**
```json
{
  "action": "ASK",
  "questionToAsk": "平时跑塑胶跑道、水泥路、还是都有？"
}
```

`action` 取值：`ASK`（继续追问）/ `READY`（槽位完整，可进推荐）

#### 3. RecAgent（商品推荐）

向量检索 + LLM 排序重排，融合用户画像。

**输入：**
```json
{
  "slots": { "category": "跑鞋", "budget": 500, "scenario": "水泥路" },
  "userProfile": { "height": 180, "weight": 75, "prevPurchases": [] }
}
```

**输出：**
```json
{
  "recommendations": [
    {
      "productId": 8821,
      "name": "Asics GEL-Contend 9",
      "price": 479,
      "reason": "GEL 缓震 + 宽鞋楦，适合你膝盖不太好的情况",
      "matchScore": 0.88,
      "attributes": { "cushion": "high", "weight": "medium" }
    }
  ],
  "explanationTone": "professional"
}
```

#### 4. SentimentAgent（情感应答）

将推荐结果包装为带情感色彩的口语化话术 + 展示卡片。

**输入：**
```json
{
  "recommendations": [],
  "userUtterance": "我膝盖不太好",
  "sessionMood": "hesitant",
  "explanationTone": "professional"
}
```

**输出：**
```json
{
  "speechText": "好，给你挑了三款缓震很出色的……",
  "displayBlocks": [{ "商品卡片1" }, { "商品卡片2" }, { "商品卡片3" }]
}
```

## 架构决策

### 模块划分
```
voice-shopping/
├── pom.xml                          # parent pom
├── voice-shopping-common            # 公共工具、常量、DTO
├── voice-shopping-infrastructure    # 基础设施层（PG、Redis、向量检索）
├── voice-shopping-ai                # AI 能力层（AgentScope、DashScope、NLS）
├── voice-shopping-business          # 业务服务层（商品、订单、用户画像）
└── voice-shopping-web               # 通信接口层（REST、WebSocket、Sa-Token）
```
依赖方向：`web → business → ai → infrastructure → common`

### 全双工音频流
WebSocket 二进制帧传 PCM 音频，文本帧传 JSON 控制消息。

### 向量检索
JdbcTemplate + 原生 SQL 操作 pgvector，不用 JPA/Hibernate 扩展。在 SQL 层完成向量检索 + JSONB 属性过滤的组合查询。

### 数据隔离
行级隔离：每张业务表加 `merchant_id` 字段，通过 MyBatis-Plus 租户插件自动注入 WHERE 条件。

## 编码规范

- 交互用中文，代码和注释用英文
- 优先使用函数式编程范式
- 错误处理遵循 fail fast 原则，严禁吞掉异常
- 禁止硬编码（中间件配置、第三方接口地址等走配置文件）
- 简洁优于巧妙，可读性第一
