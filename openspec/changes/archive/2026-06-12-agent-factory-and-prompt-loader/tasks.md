## 1. PromptLoader

- [x] 1.1 在 `voice-shopping-ai/src/main/java/com/voiceshopping/ai/agent/PromptLoader.java` 创建 PromptLoader 类，使用 `@Component` 注解，实现 `load(String path)` 方法，通过 `ClassPathResource` 和 `Files.readString` 读取 `prompts/` 目录下的文件，读取失败时抛出 `RuntimeException`

## 2. AgentFactory

- [x] 2.1 在 `voice-shopping-ai/src/main/java/com/voiceshopping/ai/agent/AgentFactory.java` 创建 AgentFactory 类，使用 `@Component` 注解
- [x] 2.2 定义内部 AgentSet 结构（包含 intent/clarify/rec/sentiment 四个 Agent 引用）
- [x] 2.3 创建 LRU 缓存：`LinkedHashMap(16, 0.75f, true)` + `removeEldestEntry`（阈值 1000），用 `Collections.synchronizedMap` 包裹
- [x] 2.4 实现 `get(sessionId)` 方法：从缓存获取或创建 AgentSet，存入 LRU 缓存后返回
- [x] 2.5 实现 `remove(sessionId)` 方法：主动清理缓存条目
- [x] 2.6 实现 `newPerspectiveTeam()` 方法：每次新建 3 个 PerspectiveAgent 实例，不走缓存
- [x] 2.7 Agent 的具体创建逻辑暂用 `null` 占位，添加 `// TODO: instantiate actual agents when AgentBuilder is implemented` 注释

## 3. Agent Builder 占位类

- [x] 3.1 创建包目录 `agent/intent/`、`agent/clarify/`、`agent/rec/`、`agent/sentiment/`、`agent/perspective/`
- [x] 3.2 创建 `IntentAgentBuilder.java`，包名 `com.voiceshopping.ai.agent.intent`，包含 `// TODO: implement in subsequent version`
- [x] 3.3 创建 `ClarifyAgentBuilder.java`，包名 `com.voiceshopping.ai.agent.clarify`，包含 `// TODO: implement in subsequent version`
- [x] 3.4 创建 `RecAgentBuilder.java`，包名 `com.voiceshopping.ai.agent.rec`，包含 `// TODO: implement in subsequent version`
- [x] 3.5 创建 `SentimentAgentBuilder.java`，包名 `com.voiceshopping.ai.agent.sentiment`，包含 `// TODO: implement in subsequent version`
- [x] 3.6 创建 `PerspectiveAgentBuilder.java`，包名 `com.voiceshopping.ai.agent.perspective`，包含 `// TODO: implement in subsequent version`

## 4. Prompt 模板占位文件

- [x] 4.1 创建 `voice-shopping-ai/src/main/resources/prompts/` 目录
- [x] 4.2 创建 `intent.txt`，内容 `# TODO: fill prompt content`
- [x] 4.3 创建 `clarify.txt`，内容 `# TODO: fill prompt content`
- [x] 4.4 创建 `rec.txt`，内容 `# TODO: fill prompt content`
- [x] 4.5 创建 `sentiment.txt`，内容 `# TODO: fill prompt content`
- [x] 4.6 创建 `perspective_price.txt`，内容 `# TODO: fill prompt content`
- [x] 4.7 创建 `perspective_pro.txt`，内容 `# TODO: fill prompt content`
- [x] 4.8 创建 `perspective_beginner.txt`，内容 `# TODO: fill prompt content`

## 5. 验证

- [x] 5.1 编译通过：`mvn compile -pl voice-shopping-ai -am`
- [x] 5.2 确认 PromptLoader 可正确加载占位 prompt 文件（可在测试中验证）
- [x] 5.3 确认 AgentFactory 的 LRU 淘汰和线程安全行为符合设计预期
