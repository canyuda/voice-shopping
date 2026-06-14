## MODIFIED Requirements

### Requirement: 短期记忆写入用户与助手 Turn

系统 SHALL 在 handle 中向 `ShortTermMemory` 按以下顺序写入 turn：

1. **不再**在意图理解之前写入 USER turn（旧版本中第 154 行的 `shortTermMemory.append("USER", utterance, ...)` MUST 被删除）。本轮当前 utterance 已由方法入参直接传给 IntentService，无需重复入库。
2. 在 EmotionResult 计算完成、合规检查通过后，写入 ASSISTANT turn（content = `safeReply.speechText()`，agent = `finalIntent.name()`，timestamp = 当前时间）。
3. 紧接 ASSISTANT turn 之后，调用注入的 `TurnSummarizer.summarize(utterance, finalIntent, safeReply.speechText())` 生成摘要，写入第二条 turn：role = `"TURN"`、content = 摘要、agent = `finalIntent.name()`、timestamp = 当前时间。

> 设计说明：保留 ASSISTANT 用于其他可观测/调试场景；新增 TURN 摘要作为下一轮 IntentService 的紧凑历史输入。删除 USER 是为了避免与 TURN 在 `recent(3)` 视窗内重复占额度。

#### Scenario: 不再前置写入 USER turn
- **WHEN** handle 进入 IntentService.classify 之前
- **THEN** ShortTermMemory 中本轮 USER 角色 turn 数量为 0（与本轮相比无新增 USER turn）

#### Scenario: ASSISTANT 与 TURN 顺序追加
- **WHEN** handle 处理完成且 finalIntent = PRODUCT_RECOMMENDATION，safeReply.speechText = "推荐 Nike Air"
- **THEN** ShortTermMemory 中本轮新增的 turn 列表（按时间序）为：
  1. `Turn(role="ASSISTANT", content="推荐 Nike Air", agent="PRODUCT_RECOMMENDATION", ts=...)`
  2. `Turn(role="TURN", content="[PRODUCT_RECOMMENDATION] 用户：... / 助手：推荐 Nike Air", agent="PRODUCT_RECOMMENDATION", ts=...)`

#### Scenario: TurnSummarizer 调用参数正确
- **WHEN** handle 接收 utterance="想买跑鞋"，finalIntent=PRODUCT_RECOMMENDATION，safeReply.speechText="推荐 Nike Air"
- **THEN** TurnSummarizer.summarize 被调用一次，参数顺序为 ("想买跑鞋", PRODUCT_RECOMMENDATION, "推荐 Nike Air")

#### Scenario: 异常路径不写入 ASSISTANT 与 TURN
- **WHEN** handle 在 dispatch 阶段抛异常
- **THEN** ShortTermMemory 不新增 ASSISTANT 或 TURN 任一条目（仅 finally 块的 Timer stop 被执行）
