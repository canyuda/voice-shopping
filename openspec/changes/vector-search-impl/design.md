## Context

语音购物系统采用 4 Worker Agent + Orchestrator 状态机架构。RecAgent 需要 pgvector 语义检索商品，FAQ 检索需要 `faq_entry` 向量匹配。当前 `voice-shopping-infrastructure` 模块已有 RedisConfig 和 JdbcTemplate（由 Spring Boot 自动配置），但尚无 Entity、Repository、向量服务的实现。

数据库表结构（`product`、`faq_entry`）及 HNSW 索引由 Flyway 管理，已在 `docs/data/table-specifications.md` 中定义。pgvector 字段类型为 `vector(1024)`，对应 DashScope text-embedding-v3 模型输出维度。

技术栈约束：
- 向量检索用 JdbcTemplate + 原生 SQL，不用 JPA/Hibernate-vector 扩展
- 向量化调用 DashScope SDK `dashscope-sdk-java:2.22.4`，模型 `text-embedding-v3`
- 模块依赖方向：`web → business → ai → infrastructure → common`

## Goals / Non-Goals

**Goals:**
- 提供 product/faq_entry 的 Entity 和 Repository，支持 pgvector 字段读写
- 封装 EmbeddingService，屏蔽 DashScope API 细节，支持单条和批量向量生成
- 实现 ProductVectorService，提供向量写入和余弦相似度检索（含 JSONB 属性过滤）
- 实现全库批量 reindex 管道（product + FAQ），并发分批调用 DashScope
- 实现 FaqVectorService.searchBest，相似度阈值 0.75 过滤
- 提供 REST 接口暴露向量检索、FAQ 问答、手动刷新向量的能力

**Non-Goals:**
- 不实现 Agent 编排逻辑（IntentAgent/ClarifyAgent/RecAgent/SentimentAgent）
- 不实现用户画像、订单、会话等模块
- 不实现 WebSocket 音频流
- 不实现认证鉴权（Sa-Token 集成）
- 不实现增量向量更新（商品创建/修改时自动触发）——仅实现手动全量 reindex

## Decisions

### D1: Entity 用 JPA 注解，但 Repository 操作向量字段时绕过 JPA

**选择：** Entity 用 `@Entity` + JPA 注解映射普通字段，`embedding` 字段用 `@Column(columnDefinition = "vector(1024)")` 声明。向量写入和检索通过 JdbcTemplate 原生 SQL 完成，pgvector 类型用 `PGobject` 手动设置。

**理由：** CLAUDE.md 明确要求"JdbcTemplate + 原生 SQL 操作 pgvector，不用 JPA/Hibernate 扩展"。pgvector 的 JDBC 类型映射需要 `PGobject`，JPA 不原生支持，强行集成需要额外 Hibernate Type 贡献包，增加复杂度。

**替代方案：** 纯 MyBatis-Plus / 纯 JdbcTemplate 不用 Entity —— 但 Entity 提供类型安全和元数据，Repository 提供基础 CRUD，与向量操作互补。

### D2: EmbeddingService 放在 infrastructure 模块而非 ai 模块

**选择：** `EmbeddingService` 放在 `voice-shopping-infrastructure` 模块的 `com.voiceshopping.infrastructure.vector` 包下。

**理由：** EmbeddingService 是一个纯粹的基础设施服务——调用外部 API、返回 float 数组。它不涉及 Agent 编排、LLM 对话等 AI 业务逻辑。按照模块依赖方向（`ai → infrastructure`），如果 RecAgent 需要同时用 EmbeddingService 和 ProductVectorService，两者都在 infrastructure 层更自然。

**替代方案：** 放在 ai 模块 —— 但这会让 infrastructure 需要反向依赖 ai 模块来使用 EmbeddingService，违反依赖方向。

### D3: 批量 reindex 用虚拟线程 + Semaphore 并发控制

**选择：** reindex 接口使用 Java 21 虚拟线程 + `Semaphore` 控制并发（默认 5 个并发 DashScope 调用），分批读取全库数据，每批 25 条调用 DashScope batch API。product 和 FAQ 的 reindex 共享同一套并发控制机制。

**理由：** DashScope text-embedding-v3 batch API 单次最多支持 25 条文本。虚拟线程轻量，适合 I/O 密集型的 API 调用。Semaphore 限制并发防止触发 API 限流。FAQ 条目数通常远少于商品，复用同一管道减少重复代码。

**替代方案：** `CompletableFuture` 线程池 —— 可行但虚拟线程在 I/O 场景更简洁；Reactor 响应式 —— 当前业务层未采用响应式模型，引入增加复杂度。

### D4: ProductVectorService 检索返回 Record DTO

**选择：** 向量检索返回 `ProductSearchResult`（Java Record），包含商品基础信息和相似度分数，不直接返回 Entity。

**理由：** 向量检索的 SQL 是原生查询，结果包含计算列（`1 - (embedding <=> ?)` 相似度分数），无法映射到 Entity。Record 不可变，与项目 DTO 规范一致。

### D5: FaqVectorService.searchBest 返回 Optional<FaqEntry>

**选择：** `searchBest` 方法返回 `Optional<FaqSearchResult>`，相似度 < 0.75 时返回 `Optional.empty()`，调用方走 LLM 兜底。

**理由：** 文档约定"相似度阈值 ≥ 0.75 才算命中，低于阈值宁可走 LLM 兜底也别答错"。Optional 明确表达"可能没找到"的语义。

### D6: SearchController 和 FaqController 放在 web 模块

**选择：** Controller 放在 `voice-shopping-web` 模块的 `com.voiceshopping.web.controller` 包下。

**理由：** 遵循模块包结构规格，Controller 属于 web 模块。Controller 调用 infrastructure 层的 VectorService。

### D7: EmbeddingService 直接用 DashScope SDK，不用 Spring AI EmbeddingModel

**选择：** 直接使用 `dashscope-sdk-java` 的 `TextEmbedding` 类调用 text-embedding-v3，不使用 Spring AI Alibaba 的 `EmbeddingModel` 接口。

**理由：** CLAUDE.md 注明"Spring AI Alibaba 自动配置与 SB4 暂不兼容，已通过 spring.autoconfigure.exclude 排除"。直接用 SDK 更可控，避免自动配置冲突。Spring AI Alibaba 的 EmbeddingModel 如果被排除，需要手动配置 bean，增加复杂度且无额外收益。

## Risks / Trade-offs

- **[DashScope API 限流]** → Semaphore 控制并发（默认 5），reindex 分批处理（每批 25 条）。限流错误时指数退避重试。
- **[向量维度不匹配]** → embedding_text 字段记录源文本，便于重新向量化。EmbeddingService 硬编码维度 1024，与表定义一致。
- **[reindex 长时间运行]** → 同步接口，虚拟线程执行。product 和 FAQ 共享 Semaphore，同时触发时并发不会翻倍。如果数据量大（>10万），考虑改为异步 + 进度查询。当前 demo 阶段数据量可控，同步即可。
- **[JdbcTemplate 向量参数序列化]** → 使用 `PGobject(type="vector", value="0.1,0.2,...")` 传递向量参数，pgvector JDBC 扩展正确解析。
- **[FAQ merchant_id=0 平台通用逻辑]** → FaqVectorService 检索时用 `merchant_id IN (0, :currentMerchant)` 条件，覆盖平台通用 + 商家私有 FAQ。
