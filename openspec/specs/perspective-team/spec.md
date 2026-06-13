# perspective-team

## Purpose

三人视角点评团能力：通过 AgentScope `MsgHub` 让"价格顾问 / 专业跑者 / 入门买家"三个角色对推荐 Top3 进行串行点评，输出口语化点评文本，作为推荐结果之外的补充表达。

## Requirements

### Requirement: MultiAgentModelConfig 注册 multiAgentChatModel Bean
系统 SHALL 在 `com.voiceshopping.ai.model` 包下提供 `MultiAgentModelConfig`，注册名为 `multiAgentChatModel` 的 `DashScopeChatModel` Bean，用于多 Agent `MsgHub` 场景。

约束：
- apiKey MUST 来源于 `@Value("${dashscope.api-key}")`，与 `AgentScopeConfig` 同源，不得硬编码。
- modelName MUST 来源于 `@Value("${dashscope.model.multi-agent:qwen-plus}")`，默认值 `qwen-plus`。
- formatter MUST 设置为 `new DashScopeMultiAgentFormatter()`。
- Bean 名 MUST 为 `multiAgentChatModel`（精确字符串）。

#### Scenario: 默认配置注入
- **WHEN** 启动时未配置 `dashscope.model.multi-agent`，但 `dashscope.api-key` 已配置
- **THEN** 容器中存在名为 `multiAgentChatModel` 的 DashScopeChatModel Bean，且 modelName 为 `qwen-plus`，formatter 类型为 `DashScopeMultiAgentFormatter`

#### Scenario: 自定义模型名覆盖
- **WHEN** 配置 `dashscope.model.multi-agent: qwen-plus-latest`
- **THEN** Bean 的 modelName 变为 `qwen-plus-latest`

#### Scenario: 缺失 apiKey 启动失败
- **WHEN** 启动时未配置 `dashscope.api-key`
- **THEN** 启动失败并提示属性缺失（与现有 `AgentScopeConfig` 一致行为）

### Requirement: PerspectiveAgentBuilder 真实实现
系统 SHALL 修改 `com.voiceshopping.ai.agent.perspective.PerspectiveAgentBuilder.build(String name, String sysPrompt)` 方法，返回真实可用的 `ReActAgent` 实例。

约束：
- 注入的 chat model MUST 通过 `@Qualifier("multiAgentChatModel")` 选择。
- 返回的 `ReActAgent` MUST 满足：`.name(name)`, `.sysPrompt(sysPrompt)`, `.model(<multiAgentChatModel>)`, `.memory(new InMemoryMemory())`。
- 每次 `build` 调用 MUST 返回**全新的** `InMemoryMemory` 实例，不得复用。
- 方法 MUST NOT 返回 `null`。

#### Scenario: 三次 build 互相独立
- **WHEN** 同一 builder 实例先后调用 `build("a", "p1")`、`build("b", "p2")`
- **THEN** 返回两个不同的 ReActAgent 引用，且各自的 memory 互不可见

### Requirement: PerspectiveHubService.discuss 三角色 MsgHub 顺序点评
系统 SHALL 在 `com.voiceshopping.business.perspective.PerspectiveHubService` 提供 `String discuss(String sessionId, String utterance, List<RecommendedItem> items)` 方法。

行为约束：
1. 当 `items` 为 null 或空，MUST 直接返回 `""`，不创建任何 Agent / Hub。
2. MUST 通过 `agentFactory.newPerspectiveTeam()` 获取一个**全新的** `PerspectiveTeam`，不复用任何缓存。
3. MUST 构造 announcement Msg：`name="host"`, `role=MsgRole.USER`, `textContent` 严格为：
   ```
   用户原话：<utterance>

   待点评的 Top3 商品：
   - <name> / <price> / <reason>
   - <name> / <price> / <reason>
   - <name> / <price> / <reason>

   请各位依次发言，每人 30 字以内。
   ```
   其中 `<price>` MUST 用 `BigDecimal.toPlainString()` 输出，避免科学计数法；商品列表条数等于 `items.size()`，最多 Top3。
4. MUST 用 try-with-resources 创建 `MsgHub`：
   - `name="perspective_" + sessionId`
   - `participants` 依次为 `priceAgent, proAgent, beginnerAgent`
   - `announcement` 为第 3 步构造的 Msg
   - `enableAutoBroadcast(true)`
5. MUST 调用 `hub.enter().block()`。
6. MUST 依次（**串行**，前一个完成后才发起后一个）执行三次**无参** `call().block()`：
   - `Msg priceMsg = team.priceAgent().call().block();`
   - `Msg proMsg = team.proAgent().call().block();`
   - `Msg beginnerMsg = team.beginnerAgent().call().block();`
7. MUST 拼接返回字符串，格式为（行间用 `%n`/系统换行符）：
   ```
   价格顾问：<priceMsg.getTextContent()>
   专业用户：<proMsg.getTextContent()>
   入门买家：<beginnerMsg.getTextContent()>
   ```
   单个 `Msg` 为 null 或 `getTextContent()` 为 null/blank 时，对应位置 MUST 输出空字符串而非字面量 `null`。
8. 任意步骤抛出异常时，整个方法 MUST 捕获并返回 `""`，并以 WARN 级别记录日志（含 sessionId 与异常 message），不得向上抛出。
9. try 块退出时 `MsgHub.close()` MUST 自动执行（依赖 `AutoCloseable` 实现）。

#### Scenario: 正常流程返回三段拼接文本
- **WHEN** items 含 3 个商品，三个 agent 均正常响应
- **THEN** 返回字符串以 `价格顾问：` 开头，包含 `专业用户：` 与 `入门买家：` 三个前缀，**且整体非空**

#### Scenario: items 为空快速返回
- **WHEN** 调用 `discuss("s1", "买跑鞋", List.of())` 或 `discuss("s1", "x", null)`
- **THEN** 返回 `""`，且 `agentFactory.newPerspectiveTeam()` **未被调用**

#### Scenario: 任一 agent 抛异常整体降级
- **WHEN** `team.proAgent().call()` 抛出 RuntimeException
- **THEN** 方法返回 `""`，记录 WARN 日志，且 try-with-resources 仍正常关闭 hub（不泄漏资源）

#### Scenario: getTextContent 为空时占位为空串
- **WHEN** 价格顾问 agent 返回的 Msg `getTextContent()` 为空字符串
- **THEN** 返回字符串中 `价格顾问：` 后紧跟换行符（不出现 `null`）

### Requirement: PerspectiveHubController POST /api/v1/hub/perspective
系统 SHALL 提供 `POST /api/v1/hub/perspective` HTTP 接口，请求体为 `PerspectiveHubReq(sessionId, userId, utterance, slots)`，响应为 `ApiResult<PerspectiveHubResp>`。

行为约束：
1. 请求参数 `sessionId`, `userId`, `utterance` MUST 校验非空，校验失败由全局异常处理返回 400。
2. MUST 依次：
   - 调 `recommendOrchestrator.recommend(sessionId, userId, utterance, slots)` 得到 `RecommendResult rec`。
   - 调 `perspectiveHub.discuss(sessionId, utterance, rec.items())` 得到 `String text`。
   - 返回 `ApiResult.ok(new PerspectiveHubResp(text, rec))`。
3. `slots` 为 null 时 MUST 当作 `Map.of()` 处理。
4. 响应 DTO MUST 为专用 `record`，禁止使用 `Map<String,Object>`。

#### Scenario: 正常调用返回推荐 + 点评
- **WHEN** POST `/api/v1/hub/perspective` 传入合法请求
- **THEN** 返回 200，`data.recommendation.items` 非空，`data.perspectiveText` 非空且含三个角色前缀

#### Scenario: utterance 为空校验失败
- **WHEN** POST `/api/v1/hub/perspective` 传入空 utterance
- **THEN** 返回 400

#### Scenario: 推荐为空时点评降级为空串
- **WHEN** 推荐结果为 `RecommendResult.EMPTY`
- **THEN** 返回 200，`data.perspectiveText` 为 `""`，`data.recommendation.items` 为空列表

### Requirement: 三个角色 Prompt 文件填充
系统 SHALL 在 `voice-shopping-ai/src/main/resources/prompts/perspective/` 下保有三个 prompt 文件，内容严格按以下文本（中文标点保留）。

`perspective_price.txt`：
```
你是电商导购里的"价格顾问"角色。
请围绕主持人给出的商品列表，从性价比、促销、替代款三个角度发表观点，30 字以内。
规则：
1. 直接输出观点，不要复述商品信息
2. 看到其他角色已发言的内容，可以附和或反驳，保持讨论感
3. 不要用"我认为/我觉得"开头，直说观点
```

`perspective_pro.txt`：
```
你是电商导购里的"专业跑者"角色。
请围绕主持人给出的商品列表，从运动性能、膝盖保护、场景匹配三个角度发表观点，30 字以内。
规则：
1. 用户可能有膝盖/脚踝隐患，专业参数要给出判断
2. 看到其他角色已发言的内容，可以附和或反驳
3. 不要用"我认为/我觉得"开头
```

`perspective_beginner.txt`：
```
你是电商导购里的"入门买家"角色。
请围绕主持人给出的商品列表，从上手难度、日常穿搭、心理价位三个角度发表观点，30 字以内。
规则：
1. 你代表第一次认真选鞋的普通用户
2. 看到专业跑者/价格顾问的观点后，说出自己能不能 follow
3. 不要用"我认为/我觉得"开头
```

文件 MUST NOT 含 `# TODO` 占位行。

#### Scenario: prompt 文件存在且非占位
- **WHEN** 启动时 `PromptLoader.load("perspective/perspective_price.txt")` 等三次调用
- **THEN** 各自返回的字符串非空，且不含子串 `TODO`
