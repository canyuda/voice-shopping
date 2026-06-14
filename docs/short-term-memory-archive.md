# 短期记忆归档方案：写入 `session_message`

> Status: 设计提案（未实现）
> Last updated: 2026-06-14

## 1. 问题背景

`LongTermMemoryWriter.flushOnSessionEnd` 在 PG `user_profile_dynamic` 写入成功后会
调用 `shortTermMemory.clear(sessionId)`，把 Redis 里 `vs:short_memory:{sessionId}`
的对话历史清掉作为幂等门闸 —— 这一步带来一个明显的副作用：

- **会话历史在 flush 之后立刻丢失**（原本 30min TTL 自然过期）
- `/api/v1/profile/memory/{sessionId}` 调试接口在 flush 之后查不到任何记录
- 客服回放、订单争议、bug 复盘都没有事后查询的入口

NOTES.md 已经记录了这两个风险点（参见 `docs/data/NOTES.md` 末尾段落）。这篇文档
回答它的解决方案。

## 2. 决策：复用 `session_message`，不新建 log 表

V1 schema 早就建了 `session_message`，并明确 `COMMENT 'Session dialogue history
(append-only)'`，但当前代码完全没有写入。建表后被搁置，是因为之前优先级聚焦在
"Redis 短期记忆 + PG 画像"两条主线，对话历史持久化被有意推迟（参见 archive change
`2026-06-12-user-profile-and-session-memory/design.md:24` 显式声明）。

新建 log 表的话，未来必然产生两个事实源（schema 注释里的"对话历史"
vs. JSONB 兜底表），漂移不可避免。复用 `session_message` 同时解决三件事：

1. ShortTermMemory clear 之前的对话内容有了去处（本提案目标）
2. 顺手填掉"语音通道未写消息历史"的 gap（次级收益）
3. 表设计意图与实际用途对齐，避免孤儿表长期闲置

## 3. session_message 现状回顾

```sql
-- V1__init_schema.sql:266
CREATE TABLE session_message (
    id           BIGSERIAL PRIMARY KEY,
    session_id   VARCHAR(64) NOT NULL,         -- V3 from UUID
    merchant_id  BIGINT      NOT NULL,         -- V2 dropped FK
    role         VARCHAR(16) NOT NULL CHECK (role IN ('USER','ASSISTANT','SYSTEM')),
    turn         INT         NOT NULL,
    agent_name   VARCHAR(32),
    content      TEXT        NOT NULL,
    content_audio_url VARCHAR(500),
    intent       VARCHAR(32),
    tokens       INT         NOT NULL DEFAULT 0,
    metadata     JSONB       NOT NULL DEFAULT '{}',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_session_message_session_id ON session_message (session_id, created_at);
CREATE INDEX idx_session_message_merchant   ON session_message (merchant_id);
```

唯一与本方案有冲突的约束：`role CHECK IN ('USER','ASSISTANT','SYSTEM')`。
`ShortTermMemory.Turn.role` 的合法集合是 `USER / ASSISTANT / SYSTEM / TURN`
（本 change 新增了 `TURN` 摘要角色），需要 V4 迁移放宽这个约束。

## 4. 实施方案

### 4.1 V4 迁移：放宽 role CHECK

```sql
-- V4__session_message_allow_turn_role.sql
ALTER TABLE session_message
    DROP CONSTRAINT session_message_role_check;
ALTER TABLE session_message
    ADD CONSTRAINT session_message_role_check
    CHECK (role IN ('USER','ASSISTANT','SYSTEM','TURN'));

COMMENT ON COLUMN session_message.role IS
    'USER / ASSISTANT / SYSTEM / TURN. TURN = single-line summary written by '
    'LongTermMemoryWriter on session-end archival.';
```

零数据迁移（既有数据全部都是 USER/ASSISTANT/SYSTEM 中的合法值，约束放宽不会
破坏任何已有行）。

### 4.2 新增 Entity 与 Repository

```java
// voice-shopping-infrastructure/.../entity/SessionMessage.java
@Entity
@Table(name = "session_message")
public class SessionMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "merchant_id", nullable = false)
    private Long merchantId;

    @Column(nullable = false, length = 16)
    /** allowed: USER / ASSISTANT / SYSTEM / TURN */
    private String role;

    @Column(nullable = false)
    private Integer turn;

    @Column(name = "agent_name", length = 32)
    private String agentName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "content_audio_url", length = 500)
    private String contentAudioUrl;

    @Column(length = 32)
    private String intent;

    @Column(nullable = false)
    private Integer tokens = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> metadata = new HashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    // … getters/setters
}

// voice-shopping-infrastructure/.../SessionMessageRepository.java
public interface SessionMessageRepository extends JpaRepository<SessionMessage, Long> {
    List<SessionMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
    long deleteBySessionId(String sessionId);  // 仅作为后续清理工具
}
```

### 4.3 写入时机：`LongTermMemoryWriter.doFlush` 增加 archive 步骤

新顺序（前 5 步保持不变）：

```
1. read STM (gate)                                ← 已有
2. load SessionState                              ← 已有
3. extract categories/brands                       ← 已有
4. dynamicRepo.save(dynamic)            (PG 写 ①)  ← 已有
5. profileService.evictCache(userId)              ← 已有

6. archiveTurns(sessionId, merchantId)  (PG 写 ②)  ← 新增, best-effort
7. shortTermMemory.clear(sessionId)               ← 已有, 仍在最末
```

新增 `archiveTurns` 私有方法的逻辑：

```
1. recent = shortTermMemory.recent(sessionId, MAX_INT)   // 拿全量
2. 如果空则直接 return（虽然 step 1 的门闸已保证非空）
3. 拿 SessionState.merchantId（step 2 已 load 过，复用）
4. 把 ShortTermMemory.Turn 列表按时间序映射成 SessionMessage 实体
   - turn 序号：从 1 开始递增
   - role 直接复用（USER/ASSISTANT/SYSTEM/TURN）
   - agent_name = Turn.agent（即 IntentEnum.name() 或 null）
   - intent = Turn.agent（与 agent_name 一致；表注释会更新说明）
   - content = Turn.content
   - tokens = 0（短期记忆未跟踪 token，留 0）
   - metadata = {"source":"short_term_archive","origTimestamp":Turn.timestamp.toString()}
5. sessionMessageRepository.saveAll(...)
```

### 4.4 失败容忍：archive best-effort，不阻断 clear

archive 必须 try/catch。理由：上一步 `dynamicRepo.save` 已经成功，**门闸必须释放**
（不释放下一次再触发会再加权一次品类品牌权重，污染画像）。所以即便 archive 失败：

```java
try {
    archiveTurns(sessionId, merchantId);
} catch (Exception e) {
    log.warn("Short-term archive failed (best-effort, continuing): sessionId={}",
             sessionId, e);
}
shortTermMemory.clear(sessionId);   // 必须执行
```

代价：极少量会话历史可能丢失（PG 写 ② 失败时）。但 PG 写 ① 已成功的情况下，
画像增量是收益、对话历史只是审计 nice-to-have，trade-off 合理。

### 4.5 开关与配置

```yaml
voice-shopping:
  memory:
    short-term-archive:
      enabled: true        # 默认开启；本地/测试想跑干净的画像测试可关闭
```

实现侧用 `@ConditionalOnProperty(... havingValue = "true", matchIfMissing = true)`，
关闭时 `archiveTurns` 调用直接跳过，不依赖 SessionMessageRepository Bean 是否存在。

### 4.6 不在本方案范围

- ❌ **语音通道实时写 session_message**（每轮对话同步落库）。这件事是另一个独立的
  工作量，需要改 `OrchestratorService.handle` 主链路、考虑 PG 写延迟对语音体验的
  影响、加 token 统计等等。本方案只做"会话结束的批量归档"，留出语音通道的接入
  口子但不在此实现。
- ❌ **回放接口**：`GET /api/v1/debug/session/{sessionId}/messages` 之类的查询
  接口可以下个版本再加，本方案只确保数据落库。
- ❌ **跨 merchant 的查询索引优化**：现有两个索引（`session_id+created_at`、
  `merchant_id`）足以支撑 v1 的归档场景，未来如果要做"按 merchant 全局回放"再加
  专用索引。

## 5. 决策摘要

| # | 决策 | 取舍 |
|---|------|------|
| 1 | 用 session_message 还是新建 log 表 | **session_message**（避免双事实源） |
| 2 | role CHECK 是否放宽含 TURN | **是**，V4 迁移 |
| 3 | archive 失败是否阻断 clear | **不阻断** best-effort |
| 4 | agent_name / intent 字段值 | **IntentEnum.name()**，更新表注释说明 |
| 5 | 默认开关状态 | **enabled=true** |
| 6 | 顺便做语音通道实时写消息 | ❌ 推迟到独立方案 |

## 6. 实施步骤（待提案后一次性执行）

1. 写 V4 迁移：放宽 role CHECK + 更新列注释
2. 新增 `SessionMessage` Entity + `SessionMessageRepository`
3. 改造 `LongTermMemoryWriter`：注入 `SessionMessageRepository`，新增私有
   `archiveTurns(sessionId, merchantId)`，在 evictCache 之后、clear 之前调用，
   try/catch 兜异常仅 WARN
4. 新增配置项 `voice-shopping.memory.short-term-archive.enabled` 默认 true
5. `LongTermMemoryWriterTest` 加 4 个用例：
   - 成功路径：archive 与 clear 都被调用，archive 写入与 STM 内容一致
   - archive 失败：clear 仍被调用、不抛异常
   - 开关关闭：archive 不被调用、clear 仍被调用
   - 不同 role / agent 字段映射
6. `docs/data/table-specifications.md` 更新 `session_message.role` 取值集合
7. `docs/data/NOTES.md` 末尾追加备注：archive 已落地，flush 后历史可从
   PG 查询

## 7. 与现有设计的一致性

- ✅ `session_message` COMMENT 写的就是 `Session dialogue history (append-only)`，
  本方案严格遵守 append-only 语义（只 saveAll，无 update/delete）
- ✅ ShortTermMemory.clear 仍然只在 PG ① + PG ② 都尝试过之后才执行
- ✅ 幂等门闸语义不变：clear 释放门闸的语义和优先级最高
- ✅ 与 SessionExpireListener 触发路径无冲突 —— archive 在 doFlush 内部，
  WS close / TTL expire / 手动调试三个触发点都会经过 archive

## 8. 待你确认

实施前确认决策表（§5）的取舍是否对得齐。如果都同意，开新 OpenSpec change
`add-short-term-memory-archive` 走 propose → apply 流程，本提案文档可作为
design.md 的素材。
