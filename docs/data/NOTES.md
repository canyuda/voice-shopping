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
