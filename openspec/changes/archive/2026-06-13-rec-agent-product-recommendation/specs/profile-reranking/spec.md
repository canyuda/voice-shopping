## ADDED Requirements

### Requirement: ProfileReranker 画像加权重排
系统 SHALL 在 `com.voiceshopping.business.rec` 包下提供 `ProfileReranker` 类，实现 `List<RecommendedItem> rerank(List<RecommendedItem> candidates, UserProfileSnapshot profile, Map<String, Object> slots)` 方法，按 `computeScore` 加权分值降序排列。

#### Scenario: 正常加权重排
- **WHEN** 调用 `rerank(20个候选, profile, slots)`
- **THEN** 返回 20 个 RecommendedItem，按 matchScore（含加权增量）降序排列，原 matchScore 被 `withMatchScore` 替换

#### Scenario: 候选列表为空返回空列表
- **WHEN** 调用 `rerank(空列表, profile, slots)`
- **THEN** 返回空 List

### Requirement: computeScore 加权计算
`ProfileReranker` SHALL 通过私有方法 `double computeScore(RecommendedItem item, UserProfileSnapshot p, Map<String, Object> slots)` 计算加权分值，基于原始 matchScore 做增量加减。

加权规则：

| 规则 | 条件 | 加分 |
|------|------|------|
| Budget 主推档 | budget 存在，价格在 budget × 60%~95% | +0.25 |
| Budget 中等档 | budget 存在，价格在 budget × 30%~60% 或 95%~100% | +0.05 |
| 远低于预算 | budget 存在，价格 < budget × 30% | -0.1 |
| 品牌偏好 | 商品品牌在 profile.brandPrefs 中 | +brandPrefs.get(brand) × 0.2 |
| 价格敏感 | 商品价格 > profile.avgOrderAmount × 1.5 且 priceSensitivity == "HIGH" | -0.15 |
| 近期同类购买 | recentBehavior 中存在同品牌+同类目购买记录 | -0.3 |

#### Scenario: Budget 主推档加分
- **WHEN** slots 含 budget=500，商品价格为 350（70% of budget）
- **THEN** computeScore 返回原始 matchScore + 0.25

#### Scenario: Budget 远低于预算扣分
- **WHEN** slots 含 budget=500，商品价格为 100（20% of budget）
- **THEN** computeScore 返回原始 matchScore - 0.1

#### Scenario: 品牌偏好加分
- **WHEN** profile.brandPrefs 含 {"Nike": 0.8}，商品 attributes 含 brand="Nike"
- **THEN** computeScore 返回原始 matchScore + 0.8 × 0.2 = +0.16

#### Scenario: 价格敏感扣分
- **WHEN** profile.priceSensitivity 为 "HIGH"，profile.avgOrderAmount 为 300，商品价格为 500（>300×1.5=450）
- **THEN** computeScore 返回原始 matchScore - 0.15

#### Scenario: 近期购买同类扣分
- **WHEN** profile 无 recentBehavior 字段，降级为 brand+category 匹配，使用 slots 中的 brand/category 与商品匹配
- **THEN** 如果品牌和类目均匹配，computeScore 返回原始 matchScore - 0.3

#### Scenario: 多规则叠加
- **WHEN** 商品同时满足品牌偏好和 budget 主推档
- **THEN** computeScore 返回原始 matchScore + 0.25 + 品牌加分

### Requirement: ProfileReranker 单元测试
系统 SHALL 为 `ProfileReranker.computeScore` 提供单元测试，覆盖所有加权规则的命中和未命中场景，以及边界值和多规则叠加。

#### Scenario: 单元测试覆盖所有规则
- **WHEN** 运行 ProfileRerankerTest
- **THEN** 测试类包含 budget 锚点（主推档/中等档/远低于预算/无 budget）、品牌偏好（命中/未命中）、价格敏感（命中/未命中/非 HIGH）、近期购买（命中/未命中）、多规则叠加等测试方法
