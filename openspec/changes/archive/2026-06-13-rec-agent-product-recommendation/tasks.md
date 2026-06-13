## 1. DTO 定义

- [x] 1.1 在 `common/dto/agent` 下创建 `Filter` Record（clause + params）
- [x] 1.2 在 `common/dto/agent` 下创建 `RecommendedItem` Record（productId, name, price, reason, matchScore, attributes）+ `withMatchScore` + `withReason`
- [x] 1.3 在 `common/dto/agent` 下创建 `RecommendResult` Record（items + explanationTone）

## 2. ProductVectorService 改造

- [x] 2.1 改造 `ProductVectorService.search()` 签名：移除 merchantId/minPrice/maxPrice/attributeFilters，新增 `String extraFilter, List<Object> extraParams`；SQL 模板 merchant_id 处加 TODO 注释；extraFilter 非空时追加 AND 子句
- [x] 2.2 同步改造 `SearchController`（`/api/v1/search`），使用 SqlFilterBuilder 构建过滤并调用新 search 签名

## 3. SqlFilterBuilder 过滤构建

- [x] 3.1 在 `infrastructure/vector` 下创建 `SqlFilterBuilder`，实现 `fromSlots(Map<String, Object> slots)` 方法
- [x] 3.2 实现 `runningShoeFilter(Map<String, Object> slots)` 跑鞋场景专用过滤
- [x] 3.3 实现 `merge(Filter a, Filter b)` 多过滤器合并

## 4. RecommendCandidatesService 候选检索

- [x] 4.1 在 `business/rec` 下创建 `RecommendCandidatesService`，实现 `fetchCandidates(float[] queryVector, Filter filter, int topN)` 方法，调用 ProductVectorService.search() 并将 ProductSearchResult 转换为 RecommendedItem

## 5. ProfileReranker 画像加权

- [x] 5.1 在 `business/rec` 下创建 `ProfileReranker`，实现 `rerank()` 方法和私有 `computeScore()` 方法，包含 budget 锚点、品牌偏好、价格敏感、近期购买去重（降级 brand+category 匹配）全部加权规则
- [x] 5.2 编写 `ProfileRerankerTest` 单元测试，覆盖所有加权规则的命中/未命中/边界值/多规则叠加场景

## 6. RecAgentBuilder + Prompt

- [x] 6.1 实现 `RecAgentBuilder.build()`：注入 `mainChatModel`，加载 `prompts/rec.txt`，name="recommend_agent"，使用 InMemoryMemory
- [x] 6.2 创建 `voice-shopping-ai/src/main/resources/prompts/rec.txt` 推荐理由生成 Prompt（角色定义 + 输入输出 JSON 格式）

## 7. RecommendReasonService 理由生成

- [x] 7.1 在 `business/rec` 下创建 `RecommendReasonService`，实现 `attachReasons(String sessionId, String userNeeds, List<RecommendedItem> products)` 方法：构造 JSON userMsg → 调用 agent → 解析响应 → withReason 填充 → try-catch 降级

## 8. RecommendOrchestrator 编排

- [x] 8.1 在 `business/rec` 下创建 `RecommendOrchestrator`，实现 `recommend(String sessionId, Long userId, String utterance, Map<String, Object> slots)` 方法，串联全流程（load profile → buildQuery → embed → filter → fetchCandidates → 空结果兜底 → rerank → limit 3 → attachReasons → 返回 RecommendResult）
- [x] 8.2 实现空结果渐进兜底逻辑（预算+30% → 去掉二级分类 → 返回 empty），复用 queryVector 不重新 embedding

## 9. RecommendDebugController 调试接口

- [x] 9.1 创建 `RecommendDebugReq` DTO（utterance + slots）
- [x] 9.2 在 `web/controller` 下创建 `RecommendDebugController`，实现 `POST /api/v1/agent/recommend` 接口

## 10. 编译验证

- [x] 10.1 执行 `mvn compile -pl voice-shopping-common,voice-shopping-infrastructure,voice-shopping-business,voice-shopping-ai,voice-shopping-web` 确保全模块编译通过
- [x] 10.2 执行 `mvn test -pl voice-shopping-business -Dtest=ProfileRerankerTest` 确认单元测试通过
