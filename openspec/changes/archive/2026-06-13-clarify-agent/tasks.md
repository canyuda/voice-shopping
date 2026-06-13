## 1. 槽位规则配置

- [x] 1.1 创建 `voice-shopping-ai/src/main/resources/clarify/required-slots.yml`，包含跑鞋/T恤/手表/口红和 default 规则

## 2. ClarifyRuleService

- [x] 2.1 创建 `ClarifyRuleService`，构造时读取 `clarify/required-slots.yml`，解析为 `Map<String, CategoryRule>`
- [x] 2.2 实现 `missingSlots(String category, Map<String, Object> slots)` 方法：required 缺失在前，niceToHave 缺失在后

## 3. ClarifyAgentBuilder

- [x] 3.1 创建 `prompts/clarify.txt` Prompt 文件（角色、约束、输入输出格式）
- [x] 3.2 实现 `ClarifyAgentBuilder.build()`：注入 lightChatModel + PromptLoader，返回 ReActAgent（name=clarify_agent，内存=InMemoryMemory）

## 4. ClarifyResult DTO

- [x] 4.1 在 `voice-shopping-common` 创建 `ClarifyResult` Record，包含 Action 枚举和静态工厂方法 `ready()` / `ask()`
- [x] 4.2 创建 `ClarifyDebugReq` Record

## 5. ClarifyService

- [x] 5.1 创建 `ClarifyService.decide(sessionId, utterance, slots)` 方法
- [x] 5.2 实现规则层逻辑：取 category → missingSlots → 空则 READY
- [x] 5.3 实现截断层：超过 2 个缺失字段只取前 2 个
- [x] 5.4 实现 LLM 层：获取 agent → clear memory → 构造 userMsg（格式化 knownSlots）→ call → 返回 ask
- [x] 5.5 实现 `buildUserMsg()` 私有方法：按 `- key: value` 格式拼接已知信息，NULL 值不显示

## 6. ClarifyDebugController

- [x] 6.1 创建 `ClarifyDebugController`，POST /api/v1/agent/clarify

## 7. 验证

- [x] 7.1 编译通过：`mvn compile -pl voice-shopping-web -am`
