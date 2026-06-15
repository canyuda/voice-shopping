## MODIFIED Requirements

### Requirement: WebSocketConfig 端点注册

WebSocketConfig SHALL 实现 `WebSocketConfigurer`，注册 `VoiceWebSocketHandler` 到路径 `/ws/voice`，并 SHALL 注册 `AuthHandshakeInterceptor`（取代旧的 `VoiceHandshakeInterceptor`）作为握手拦截器。调试阶段 SHALL 允许所有 origin。

注册的 sub-protocol 列表 SHALL 包含**通配匹配** `Bearer-*` 形式 —— 由于 Spring `setSupportedProtocols` 不支持通配，实际实现可在拦截器内自行验证 prefix 并通过响应头回写完成 RFC 6455 协商，registry 上不强制声明 supportedProtocols。

#### Scenario: 端点可连接
- **WHEN** 浏览器使用 `new WebSocket("ws://localhost:8080/ws/voice?sessionId=s1", ['Bearer-' + token])` 且 token 合法
- **THEN** WebSocket 握手成功，连接建立，响应头含 `Sec-WebSocket-Protocol: Bearer-{token}`

#### Scenario: 旧 query userId 不再被信任
- **WHEN** 浏览器使用 `new WebSocket("ws://localhost:8080/ws/voice?userId=1&sessionId=s1")` 不带 sub-protocol
- **THEN** 握手失败 401（缺失 sub-protocol token）
