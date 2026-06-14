# 临时记录

## UserProfileService 合并策略

`user_profile_static` 和 `user_profile_dynamic` 两张表在 Service 层通过 `UserProfileService.load(userId)` 合并为一个 `UserProfileSnapshot` DTO，供 Agent 使用。

## session_state 双写策略

`session_state` 在 Redis 里同步一份（`vs:session:{sessionId}`），Redis 做快路径，PG 做持久化兜底。Redis 过期了从 PG 恢复，不会丢会话。

## session.merchant_id 可空

`session.merchant_id` 可空——平台入口（不绑商家）的会话留 NULL；商详页或商家小程序进来的绑死商家 ID。多商家数据隔离全靠这个字段。

## session_message 不存原始音频

`session_message` 只存文本，不存原始音频——音频走对象存储，库里只留 `content_audio_url`。

## session.outcome 转化漏斗

`outcome` 维度看转化漏斗：
- **ORDER** — 真正下单的
- **ABANDONED** — 中途流失
- **FOLLOWUP** — 有追问未结

按这三档统计就能看到漏斗形状。

## LongTermMemoryWriter 用 ShortTermMemory 当幂等门闸的取舍

`LongTermMemoryWriter.flushOnSessionEnd` 在写入 PG 成功后会调用 `shortTermMemory.clear(sessionId)`，下一次相同 `sessionId` 触发（WS close / order confirm / Redis TTL listener 多源都可能再来）会读到空 ShortTermMemory，直接 return 空跑——不需要 SETNX 或独立的 flushed 标记。

已知代价：

- **ShortTermMemory 内容会丢失**：会话级临时数据 TTL 本来 30min 就会丢，`clear` 只是把"30min 后丢"提前到"flush 后丢"。一些以前能查到的内容（例如 flush 后回看 30min 内的对话）会查不到。
- **可观测性轻微下降**：flush 之后 `/api/v1/profile/memory/{sessionId}` 查不到该会话的对话历史。该接口的设计语义是"实时调试 / 进行中会话观察"，flush 时会话已经结束，调试价值本就很低；但需要做"事后回放对话"的功能时，应当走 PG `session_message` 表（注：当前语音通道还没把对话写入 `session_message`，是另一个独立的 gap）。

幂等的另一面：clear 仅在 PG 写入成功后执行（`dynamicRepo.save → evictCache → clear` 顺序，前置失败/异常都不 clear），保证 PG 没落盘的会话不会被错误地标记为"已 flush"。
