## MODIFIED Requirements

### Requirement: 状态机驱动 UI

voice-test.html SHALL 使用状态机管理整条链路：`IDLE → CONNECTING → RECORDING → WAITING → CLOSING → IDLE`。每个状态的按钮文案、颜色、可点击状态 SHALL 自动切换，防止状态错乱。

**[CHANGED]** WAITING 状态不再等待单个 `agent_status=done`，而是等待 `stream_done` 信令。接收 `stream_text` 时 SHALL 实时渲染字幕（打字机效果），接收 `stream_products` 时 SHALL 渲染产品卡片区域。

#### Scenario: 流式信令驱动 UI
- **WHEN** WebSocket 收到 `stream_products` 信令
- **THEN** 产品卡片区域渲染对应商品卡片
- **WHEN** WebSocket 收到 `stream_text` 信令
- **THEN** 字幕区域追加显示文字（打字机效果）
- **WHEN** WebSocket 收到 `stream_done` 信令
- **THEN** 状态回到 IDLE

## ADDED Requirements

### Requirement: 产品卡片区域

voice-test.html SHALL 在录音控制按钮下方、日志区上方新增产品卡片区域（`#product-cards`），接收 `stream_products` 信令后渲染商品卡片列表。

每个卡片 SHALL 展示：商品名称、价格、属性标签。卡片布局 SHALL 使用横向滚动或 grid 布局，最多展示 5 个商品。

#### Scenario: 3 件商品渲染为 3 张卡片
- **WHEN** 收到 stream_products 含 3 个 item
- **THEN** 产品卡片区域显示 3 张卡片，每张含 name + price + attributes 标签

#### Scenario: 无商品时卡片区域隐藏
- **WHEN** 未收到 stream_products 信令
- **THEN** 产品卡片区域不可见

### Requirement: 字幕打字机区域

voice-test.html SHALL 在产品卡片区域下方新增字幕区域（`#caption`），接收 `stream_text` 信令后逐段追加显示文字。

字幕区域 SHALL：
- 实时追加文字，模拟打字机效果
- 字体大小适合远距离阅读（≥20px）
- 文字颜色与当前暗色主题协调
- 流结束后字幕保留，直到下一轮录音开始时清空

#### Scenario: 字幕逐步追加
- **WHEN** 连续收到 stream_text "别纠结，" → "鸡哥给你挑了三款。"
- **THEN** 字幕区域显示 "别纠结，鸡哥给你挑了三款。"

#### Scenario: 新录音清空字幕
- **WHEN** 用户开始新一轮录音
- **THEN** 字幕区域和产品卡片区域被清空

### Requirement: 音频与字幕同步播放

TTS PCM 二进制通道 SHALL 保持不变，`playPcm()` 逻辑不变。字幕文字与音频 SHALL 自然同步——文字先于或同步于音频到达前端。

#### Scenario: 字幕先于音频
- **WHEN** 收到 stream_text "别纠结，" 后 50ms 收到对应 PCM 帧
- **THEN** 字幕已显示该文字，音频无缝接续播放
