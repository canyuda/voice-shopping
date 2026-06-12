# ASR 主动关闭方案（stop 信令）

## 背景

当前前端 `stop()` 后发 800ms 静音帧依赖 Paraformer VAD 触发 `isSentenceEnd`，但全零 PCM 未必触发 VAD，导致 ASR 闲置到服务端 23 秒超时。

方案 A（已实现）用 3s 定时器兜底，治标。本方案从根源解决：前端主动通知后端关闭 ASR。

## 时序

```
用户点停止 → 发静音帧 → 发 TextMessage{"type":"stop"} → 后端 ASR.stop() → onComplete → 正常关闭
```

## 前端改动（voice-test.html）

### 1. stop() 中发完静音帧后追加 stop 信令

```javascript
// 发完静音帧后
if (ws && ws.readyState === WebSocket.OPEN) {
  ws.send(JSON.stringify({ type: "stop" }));
  log('[状态] 已发送 stop 信令', 'log-system');
}
```

### 2. onmessage 新增 stop_ack 处理（可选）

```javascript
case 'stop_ack':
  log('[状态] 后端已确认 ASR 关闭', 'log-system');
  break;
```

## 后端改动（VoiceWebSocketHandler.java）

### 1. handleTextMessage 新增 stop 处理

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    String payload = message.getPayload();
    JsonNode node = objectMapper.readTree(payload);
    if ("stop".equals(node.path("type").asText())) {
        String sessionId = session.getId();
        log.info("Received stop signal from session {}", sessionId);
        ASRService sessionAsr = sessionAsrMap.get(sessionId);
        if (sessionAsr != null) {
            sessionAsr.stop();
        }
        sendTextSafely(session, Map.of("type", "stop_ack"));
        return;
    }
}
```

### 2. 同样给 afterConnectionEstablished 中的 ASR onComplete 事件加上 session 清理

```java
() -> {
    log.debug("ASR stream completed for session {}", sessionId);
    // send stop_ack if not already sent
}
```

## 与方案 A 的关系

- 方案 B 是根本方案，主动关闭 ASR，不依赖 VAD 行为
- 方案 A 保留作为安全网，双重保障
- 如果方案 A 的 3s 兜底在生产环境频繁触发，说明应启用方案 B
