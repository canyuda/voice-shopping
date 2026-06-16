## MODIFIED Requirements

### Requirement: CHITCHAT 分支闲聊兜底

意图为 CHITCHAT 时，系统 SHALL **不调用** `EmotionService.wrap`，直接通过 `ChitchatReplyPool.randomReply()` 从预定义的 10 条文案池中随机抽一句作为 speechText 返回。

`runChitchat(sessionId, utterance)` 行为约束：
- MUST NOT 调用 `emotionService.wrap`（移除上版本"先调 LLM 再判断兜底"的浪费逻辑）
- MUST 调用 `ChitchatReplyPool.randomReply()` 取兜底文案
- MUST 返回 `BranchOutcome(new EmotionResult(reply, List.of()), SessionPhase.INTENT, null, null)`
- displayBlocks 固定为 `List.of()`（CHITCHAT 不展示商品）

#### Scenario: 闲聊不调用 EmotionAgent
- **WHEN** 意图为 CHITCHAT
- **THEN** `EmotionService.wrap` 不被调用一次（验证：mock emotionService 后 `verify(emotionService, never()).wrap(...)`）

#### Scenario: 返回池内随机文案
- **WHEN** runChitchat 被调用
- **THEN** 返回的 BranchOutcome.reply().speechText() 属于 ChitchatReplyPool.REPLIES 集合

#### Scenario: phase 不变
- **WHEN** runChitchat 返回 BranchOutcome
- **THEN** outcome.phase() == "INTENT"，与上版本一致

#### Scenario: 多次连续闲聊不死板
- **WHEN** 同一会话连续触发 5 次 CHITCHAT
- **THEN** 5 次返回的 speechText SHOULD 至少出现 2 种不同文案（统计期望，非强制）

### Requirement: streamHandle 流式处理 CHITCHAT 分支

`streamHandle` 在 CHITCHAT 分支下 SHALL 走 `singleTurnFlux` 通道（已有），由于 `runChitchat` 不再调 LLM，speechText 已是模板文案，TEXT 帧 + AUDIO 帧正常下发。

#### Scenario: CHITCHAT 流式输出包含 TEXT + AUDIO
- **WHEN** streamHandle 处理 CHITCHAT 意图
- **THEN** Flux 中 emit 1 个 TEXT 帧（含池内随机文案） + N 个 AUDIO 帧（TTS 合成的 PCM）
- **THEN** 不 emit PRODUCTS 帧（CHITCHAT 不带商品）
