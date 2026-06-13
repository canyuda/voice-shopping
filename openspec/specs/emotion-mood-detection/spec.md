## Purpose

SessionMoodDetector 纯规则优先级链，不依赖 LLM 即可识别会话情绪（催促/否定/肯定/犹豫/中性）。

## Requirements

### Requirement: SessionMoodDetector 纯规则优先级链

系统 SHALL 在 `com.voiceshopping.business.agent` 提供 `SessionMoodDetector.detect(String sessionId, String currentUtterance)` 方法，返回 `neutral / hesitant / impatient / positive / negative` 之一，且全程不调用任何 LLM（不依赖 `lightChatModel`）。

检测 SHALL 按以下优先级链短路返回：
1. `byRule(utterance)`：按 `IMPATIENT > NEGATIVE > POSITIVE` 顺序匹配正则，命中即返回对应标签。
2. 犹豫检测：取 `ShortTermMemory.recent(sessionId, 3)`，过滤 `role=ASSISTANT` 的 `content`，问号结尾条数 ≥2 时返回 `hesitant`。
3. 兜底返回 `neutral`。

`currentUtterance` 为 null 时 `byRule` SHALL 返回 null，继续进入后续链路。

#### Scenario: 命中催促情绪
- **WHEN** detect 收到 utterance="算了吧，太慢了"
- **THEN** 返回 "impatient"（IMPATIENT 优先匹配）

#### Scenario: 命中否定情绪
- **WHEN** detect 收到 utterance="这双太贵了"，且历史无犹豫信号
- **THEN** 返回 "negative"

#### Scenario: 命中肯定情绪
- **WHEN** detect 收到 utterance="嗯，挺好的"，且历史无犹豫信号
- **THEN** 返回 "positive"

#### Scenario: IMPATIENT 优先于 NEGATIVE
- **WHEN** detect 收到 utterance="不要了"（同时命中 IMPATIENT 的"不要"）
- **THEN** 返回 "impatient"

#### Scenario: 规则未命中且助手连续追问
- **WHEN** utterance 规则未命中，且最近 3 条对话中 role=ASSISTANT 的 content 有 2 条以问号结尾
- **THEN** 返回 "hesitant"

#### Scenario: 全部未命中兜底
- **WHEN** utterance 规则未命中，且无连续追问信号
- **THEN** 返回 "neutral"

#### Scenario: 纯规则无 LLM 依赖
- **WHEN** 任意 detect 调用
- **THEN** 不触发 lightChatModel 或任何 DashScopeChatModel 调用

### Requirement: byRule 正则集

`byRule` SHALL 使用以下正则集（任一子串命中即触发，大小写不敏感仅对 ok 适用）：

- IMPATIENT: `算了|不要|别说了|快点|到底|跳过`
- NEGATIVE: `贵|太贵|不喜欢|丑|不行|不对`
- POSITIVE: `好的|可以|不错|挺好|行|ok`

#### Scenario: IMPATIENT 正则命中
- **WHEN** byRule 收到含"快点"或"跳过"的文本
- **THEN** 返回 "impatient"

#### Scenario: NEGATIVE 正则命中
- **WHEN** byRule 收到含"太贵"或"丑"的文本（且不含 IMPATIENT 词）
- **THEN** 返回 "negative"

#### Scenario: 输入为 null
- **WHEN** byRule 收到 null
- **THEN** 返回 null
