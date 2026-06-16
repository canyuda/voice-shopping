## ADDED Requirements

### Requirement: ChitchatReplyPool 兜底文案池

系统 SHALL 在 `com.voiceshopping.business.agent` 包下提供 `ChitchatReplyPool` final class，仅包含 static 方法，不允许实例化（私有构造器）。

类内部 MUST 持有一个 `private static final List<String> REPLIES`，包含以下 10 条预定义文案（顺序不强制）：

1. `这话题我接不上，要不你说想看点啥？`
2. `我更擅长帮你挑东西，要不试试？`
3. `这个不太懂，咱聊点你想买的呗？`
4. `嗯，你想找啥商品我可以帮你看看`
5. `这事我搞不定，购物的话尽管问`
6. `聊不来这个，要不告诉我你想买啥`
7. `我帮你挑商品比较在行，说说需求？`
8. `这个跳过吧，你想买点什么？`
9. `我对这个没研究，购物的话可以聊`
10. `这话题超纲了，要不看看商品？`

提供一个 static 方法 `public static String randomReply()`：使用 `ThreadLocalRandom.current().nextInt(REPLIES.size())` 随机抽取一条返回。

#### Scenario: REPLIES 包含 10 条预定义文案
- **WHEN** 通过反射读取 ChitchatReplyPool.REPLIES 字段
- **THEN** List.size() == 10，每条文案均不为空且不超过 30 字

#### Scenario: randomReply 返回池内文案
- **WHEN** 调用 `ChitchatReplyPool.randomReply()` 任意次
- **THEN** 返回的字符串始终在 REPLIES 列表中

#### Scenario: randomReply 多次调用具有随机性
- **WHEN** 连续调用 `ChitchatReplyPool.randomReply()` 100 次
- **THEN** 返回结果 SHOULD 出现至少 5 种不同文案（统计上几乎不可能全部相同）

#### Scenario: 类不可实例化
- **WHEN** 试图通过 `new ChitchatReplyPool()` 创建实例
- **THEN** 编译期失败（构造器 private） 或运行期反射时报错

### Requirement: 文案池字符长度约束

每条文案 MUST 满足：
- 长度 ≤ 30 字（中文字符）
- 不包含换行/Markdown/特殊标记字符
- 不出现"亲"/"宝"等带货客服腔
- 不出现"哈哈"/"嘿嘿"等过度热情语气词
- 都引导回购物场景（包含"购物"/"商品"/"挑"/"买"等关键词）

#### Scenario: 文案长度合规
- **WHEN** 遍历 REPLIES 中每一条
- **THEN** `reply.length() ≤ 30` 全部成立

#### Scenario: 文案引导回购物
- **WHEN** 遍历 REPLIES 中每一条
- **THEN** 至少匹配 `"购物"|"商品"|"挑"|"买"|"想看"|"想找"` 之一

### Requirement: 文案不需要持久化或动态加载

ChitchatReplyPool 的文案 MUST 硬编码在 Java 源文件中，不通过外部配置文件加载，理由：
- 修改频率极低，PR 评审更可控
- 启动期零开销
- 文案池规模小（10 条），代码可读性好

#### Scenario: 不读取外部配置
- **WHEN** 调用 `ChitchatReplyPool.randomReply()`
- **THEN** 不访问 application.yml、数据库、Redis 或其他外部配置源
