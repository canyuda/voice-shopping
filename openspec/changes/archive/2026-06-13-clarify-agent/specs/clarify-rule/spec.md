## ADDED Requirements

### Requirement: YAML 必填槽位配置表

系统 SHALL 在 `voice-shopping-ai/src/main/resources/clarify/required-slots.yml` 提供按品类维护的必填槽位配置表。

每个品类 SHALL 包含：
- `required`：必填槽位列表
- `nice_to_have`：建议槽位列表
- `scenario_options`：可选场景列表

配置表 SHALL 包含 `default` 兜底规则。

#### Scenario: 已知品类命中
- **WHEN** 品类为"跑鞋"
- **THEN** required 为 `[category, scenario, budget]`，nice_to_have 为 `[brand, gender]`

#### Scenario: 未知品类兜底
- **WHEN** 品类不在配置表中
- **THEN** 使用 `default` 规则（required: `[category, budget]`，nice_to_have: `[brand]`）

### Requirement: ClarifyRuleService 计算缺失槽位

系统 SHALL 提供 `ClarifyRuleService` 组件，构造时读取 `required-slots.yml` 并解析为内部规则表。

`missingSlots(category, slots)` 方法 SHALL 返回 slots 中值为 null 或不存在的 required + nice_to_have 字段列表，required 优先级高于 nice_to_have。

#### Scenario: 部分缺失
- **WHEN** category="跑鞋"，slots 包含 category 和 budget，但缺失 scenario
- **THEN** 返回 `["scenario"]`（required 中缺 scenario，nice_to_have 中缺 brand、gender 排在后面）

#### Scenario: 全部缺失
- **WHEN** category="跑鞋"，slots 仅有 category
- **THEN** 返回 `["scenario", "budget", "brand", "gender"]`

#### Scenario: 全部满足
- **WHEN** category="跑鞋"，slots 包含 category、scenario、budget、brand、gender
- **THEN** 返回空列表

#### Scenario: 未知品类使用 default
- **WHEN** category="电动牙刷"（不在配置表中）
- **THEN** 使用 default 规则，检查 category、budget 和 brand
