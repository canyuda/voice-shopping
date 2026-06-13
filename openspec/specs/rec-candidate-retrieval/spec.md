# rec-candidate-retrieval

## Purpose

Recommendation candidate retrieval capability: SQL filter construction (SqlFilterBuilder), recommendation DTOs (Filter / RecommendedItem / RecommendResult), and first-stage candidate retrieval from pgvector product pool via RecommendCandidatesService.

## Requirements

### Requirement: Filter Record 定义
系统 SHALL 在 `com.voiceshopping.common.dto.agent` 包下定义 `Filter` Record，包含 `String clause`（SQL WHERE 子句片段）和 `List<Object> params`（对应参数值）。

#### Scenario: Filter 持有 SQL 片段和参数
- **WHEN** 创建 `new Filter("price <= ?", List.of(500))`
- **THEN** `clause()` 返回 `"price <= ?"`，`params()` 返回 `[500]`

### Requirement: RecommendedItem Record 定义
系统 SHALL 在 `com.voiceshopping.common.dto.agent` 包下定义 `RecommendedItem` Record，包含 `Long productId`、`String name`、`BigDecimal price`、`String reason`、`double matchScore`、`Map<String, Object> attributes`，并提供 `withMatchScore(double)` 和 `withReason(String)` 方法返回新实例。

#### Scenario: withMatchScore 返回新实例
- **WHEN** 调用 `item.withMatchScore(0.95)`
- **THEN** 返回新 RecommendedItem 实例，matchScore 为 0.95，其余字段不变

#### Scenario: withReason 返回新实例
- **WHEN** 调用 `item.withReason("缓震适合膝盖不好")`
- **THEN** 返回新 RecommendedItem 实例，reason 为 "缓震适合膝盖不好"，其余字段不变

### Requirement: RecommendResult Record 定义
系统 SHALL 在 `com.voiceshopping.common.dto.agent` 包下定义 `RecommendResult` Record，包含 `List<RecommendedItem> items` 和 `String explanationTone`。

#### Scenario: RecommendResult 持有推荐列表
- **WHEN** 创建 `new RecommendResult(top3, "professional")`
- **THEN** `items()` 返回 3 个推荐商品，`explanationTone()` 返回 "professional"

### Requirement: SqlFilterBuilder 通用过滤构建
系统 SHALL 在 `com.voiceshopping.infrastructure.vector` 包下提供 `SqlFilterBuilder` 类，实现 `Filter fromSlots(Map<String, Object> slots)` 方法，根据 slots 中的键值对生成 SQL WHERE 片段。

#### Scenario: slots 含 budget 生成价格过滤
- **WHEN** 调用 `fromSlots(Map.of("budget", 500))`
- **THEN** 返回 `Filter("price <= ?", List.of(500))`

#### Scenario: slots 含 brand 生成属性过滤
- **WHEN** 调用 `fromSlots(Map.of("brand", "Nike"))`
- **THEN** 返回 `Filter("attributes @> CAST(? AS jsonb)", List.of("{\"brand\":\"Nike\"}"))`

#### Scenario: slots 为空返回空 Filter
- **WHEN** 调用 `fromSlots(Map.of())`
- **THEN** 返回 `Filter("", List.of())`

### Requirement: SqlFilterBuilder 跑鞋场景过滤
系统 SHALL 提供 `Filter runningShoeFilter(Map<String, Object> slots)` 方法，针对跑鞋场景生成专用过滤条件（terrain、cushion、surface、scenario 等 attributes 字段）。scenario 按水泥路/塑胶跑道/越野分别映射不同的 cushion + terrain 组合，其他/未知不加条件。

#### Scenario: 跑鞋场景含路面类型过滤
- **WHEN** 调用 `runningShoeFilter(Map.of("terrain", "road"))`
- **THEN** 返回包含 `attributes @> '{"terrain":"road"}'` 的 Filter

#### Scenario: scenario 水泥路生成 cushion+terrain 组合
- **WHEN** 调用 `runningShoeFilter(Map.of("scenario", "水泥路"))`
- **THEN** 返回包含 `attributes->>'cushion' IN ('high','medium') AND (attributes->>'terrain' IS NULL OR attributes->>'terrain' IN ('road'))` 的 Filter

#### Scenario: 跑鞋场景无特定过滤返回空 Filter
- **WHEN** 调用 `runningShoeFilter(Map.of())`
- **THEN** 返回 `Filter("", List.of())`

### Requirement: SqlFilterBuilder 多过滤器合并
系统 SHALL 提供 `Filter merge(Filter a, Filter b)` 方法，合并两个 Filter 的 clause 和 params。

#### Scenario: 合并两个非空 Filter
- **WHEN** 调用 `merge(new Filter("price<=?", List.of(500)), new Filter("attributes@>?", List.of("{\"brand\":\"Nike\"}")))`
- **THEN** 返回 `Filter("price<=? AND attributes@>?", List.of(500, "{\"brand\":\"Nike\"}"))`

#### Scenario: 其中一个为空 Filter
- **WHEN** 调用 `merge(new Filter("price<=?", List.of(500)), new Filter("", List.of()))`
- **THEN** 返回 `Filter("price<=?", List.of(500))`

### Requirement: RecommendCandidatesService 候选检索
系统 SHALL 在 `com.voiceshopping.business.rec` 包下提供 `RecommendCandidatesService`，实现 `List<RecommendedItem> fetchCandidates(float[] queryVector, Filter filter, int topN)` 方法，调用 ProductVectorService 向量检索并转换为 RecommendedItem 列表。

#### Scenario: 正常检索返回候选集
- **WHEN** 调用 `fetchCandidates(queryVector, filter, 20)` 且数据库有匹配商品
- **THEN** 返回最多 20 个 RecommendedItem，matchScore 为 cosine similarity 值

#### Scenario: 无匹配返回空列表
- **WHEN** 调用 `fetchCandidates(queryVector, filter, 20)` 且无匹配商品
- **THEN** 返回空 List

### Requirement: buildQuery 关键词拼接
`RecommendOrchestrator` 中 SHALL 提供 `buildQuery(String utterance, Map<String, Object> slots)` 方法，仅提取 slots 中的属性值（category、brand、usage 等关键词）拼接为 embedding 查询文本，不拼接用户原话。

#### Scenario: slots 含关键词拼接查询
- **WHEN** 调用 `buildQuery("我想买双跑鞋", Map.of("category", "跑鞋", "budget", 500))`
- **THEN** 返回包含 "跑鞋" 的查询文本，不包含 "我想买双"

#### Scenario: slots 为空使用原话
- **WHEN** 调用 `buildQuery("帮我推荐跑鞋", Map.of())`
- **THEN** 返回 "帮我推荐跑鞋"
