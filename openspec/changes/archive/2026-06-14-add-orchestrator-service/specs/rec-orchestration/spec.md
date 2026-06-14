## ADDED Requirements

### Requirement: priceMin slot 过滤

`RecommendCandidateRetriever.buildFilter(slots)` SHALL 识别 slots 中的 `priceMin`（数值类型，BigDecimal / Number）字段，并将其转换为 SQL 过滤条件 `price >= :priceMin`。`SqlFilterBuilder` MUST 增加对应分支生成该 SQL 片段。

`priceMin` 与 `budget` 互不替代：当二者同时存在时，候选商品 MUST 同时满足 `price >= priceMin AND price <= budget`。

#### Scenario: 单独提供 priceMin

- **WHEN** slots = `{category: "跑鞋", priceMin: 500}`
- **THEN** 检索 SQL WHERE 子句包含 `price >= 500`，结果 MUST NOT 包含 price < 500 的商品

#### Scenario: priceMin 与 budget 共存

- **WHEN** slots = `{category: "跑鞋", priceMin: 500, budget: 1000}`
- **THEN** 检索结果 MUST 满足 `500 <= price <= 1000`

#### Scenario: priceMin 缺失时不影响

- **WHEN** slots = `{category: "跑鞋", budget: 500}`
- **THEN** 检索行为与新增本字段前一致

### Requirement: excludeProductIds slot 过滤

`RecommendCandidateRetriever.buildFilter(slots)` SHALL 识别 slots 中的 `excludeProductIds`（List<Long> 或 List<Number>）字段，并转换为 SQL 过滤条件 `id NOT IN (:excludeProductIds)`。`SqlFilterBuilder` MUST 增加对应分支。

空列表 MUST 等价于不施加该过滤；非空列表 MUST 排除其中所有商品。

#### Scenario: 排除上轮已推商品

- **WHEN** slots.excludeProductIds = [8821, 8822, 8823]
- **THEN** 检索结果 MUST NOT 包含这三个商品

#### Scenario: 空列表不影响

- **WHEN** slots.excludeProductIds = []
- **THEN** 检索行为与不传该字段一致
