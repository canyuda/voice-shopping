## REMOVED Requirements

### Requirement: RecAgentBuilder 实现

**Reason**: 上版本 (latency-optimization-h5-streaming) 已经把 `RecommendReasonService` 从主链路摘除，由合并的 EmotionAgent 直接生成推荐理由。`RecAgentBuilder`、`prompts/rec.txt`、`AgentFactory.recAgent` 字段、`AgentFactory.getRecAgent` 方法、`AgentMemoryPolicy.beforeRecommendCall` 方法及关联测试当时保留作为回滚资产，至今未触发回滚。本期清理这些死代码，回滚改走 git revert。

**Migration**: 无需迁移。`RecommendReasonService` 在主链路已不再被调用，删除不会影响业务行为。如需回滚到旧推荐理由生成逻辑，使用 `git revert <本期提交>` 恢复。

### Requirement: 推荐理由 Prompt 文件

**Reason**: 同上，`prompts/rec.txt` 在主链路已不再被加载，`EmotionAgentBuilder` 当前使用的是 `prompts/emotion-merged.txt`。

**Migration**: 无需迁移。

### Requirement: RecommendReasonService 理由生成

**Reason**: 同上，主链路已直接调用合并版 `EmotionStreamingService`，`RecommendReasonService.attachReasons` 不再被调用。

**Migration**: 无需迁移。
