## 待处理问题

### ASR: 23 秒超时

**现象**:
- `task-failed: request timeout after 23 seconds`
- 发生在用户停止说话后

**根因**: DashScope Paraformer 服务端 23 秒静音超时，连接未正确关闭。前端 `stop()` 补了 800ms 静音帧但 ASR 可能未触发 `isSentenceEnd`，导致连接闲置到服务端超时。

**待排查**:
- 前端 800ms 静音帧是否实际发出
- ASR `stop()` 是否在 `userStopped` 后正确调用
