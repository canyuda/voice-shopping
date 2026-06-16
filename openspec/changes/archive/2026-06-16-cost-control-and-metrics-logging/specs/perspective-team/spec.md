## MODIFIED Requirements

### Requirement: 多视角点评团开关

系统 SHALL 通过 Spring 配置 `voice-shopping.perspective.enabled`（boolean）决定是否启用 PerspectiveHubService。

**[CHANGED]** 默认值从 `true` 改为 `false`。

- 开关 false（默认）：MUST NOT 调用 PerspectiveHubService，不产生 perspective 相关 LLM 成本
- 开关 true：MUST 仅在 PRODUCT_RECOMMENDATION 分支、商品推荐之后、情感应答之前调用一次

代码（PerspectiveAgentBuilder / PerspectiveHubService / MultiAgentModelConfig / multiAgentChatModel Bean）保留不删，方便未来 A/B 测试或转化率验证时一行配置即可恢复。

#### Scenario: 开关默认关闭时不调用
- **WHEN** application.yml 未显式配置 `voice-shopping.perspective.enabled`
- **THEN** Bean 注入时 perspectiveEnabled=false
- **THEN** PRODUCT_RECOMMENDATION 分支不调用 `PerspectiveHubService.discuss`

#### Scenario: 开关显式开启时拼接到 utterance
- **WHEN** voice-shopping.perspective.enabled = true，PerspectiveHubService 返回非空多视角文本
- **THEN** EmotionService.wrap 接收的 utterance 参数 = 原始 utterance + "\n\n[多视角点评]\n" + 该文本

#### Scenario: 开关关闭时 multiAgentChatModel Bean 仍存在但不被使用
- **WHEN** Spring 容器启动，perspective.enabled=false
- **THEN** 容器中仍存在 `multiAgentChatModel` Bean（被 PerspectiveAgentBuilder 注入），但 PerspectiveHubService.discuss 不被调用，故该 Bean 实际不发起 DashScope 请求
