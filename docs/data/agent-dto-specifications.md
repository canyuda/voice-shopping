# Agent DTO 契约规格

所有 DTO 定义在 `voice-shopping-common` 模块，包路径 `com.voiceshopping.common.dto.agent`。

**所有 DTO 一律用 Java Record，不用 class。** Record 是不可变值对象，天然防止 Agent 间共享可变状态。任何需要修改的场景都是创建新实例，不是原地改字段。

**扩展字段时向后兼容。** Record 不能加字段（会破坏签名），所以用 `with...()` 方法返回新实例来扩展：

```java
// RecommendedItem 原始定义
public record RecommendedItem(long productId, String name, BigDecimal price, ...) {}

// 需要扩展时，加 with 方法而不是改签名
public record RecommendedItem(long productId, String name, BigDecimal price, ...) {
    public RecommendedItem withMatchScore(double matchScore) {
        return new RecommendedItem(productId, name, price, ..., matchScore, attributes);
    }
}
```

如果字段新增量太大，则新建一个 Record（如 `RecommendedItemV2`），不直接改旧的。

---

## 1. Intent — 意图枚举

**定义位置：** IntentAgent 输出

| 枚举值 | 说明 |
|--------|------|
| `PRODUCT_RECOMMENDATION` | 商品推荐请求 |
| `CLARIFY_NEEDED` | 信息不足，需要澄清 |
| `PRODUCT_COMPARE` | 商品对比 |
| `CHITCHAT` | 闲聊 |
| `ORDER_CONFIRM` | 确认下单 |
| `OUT_OF_SCOPE` | 超出业务范围 |

---

## 2. IntentResult — 意图识别输出

**定义位置：** IntentAgent 输出

| 字段 | 类型 | 说明 |
|------|------|------|
| intent | Intent | 识别到的意图类别 |
| slots | Map<String, Object> | 从用户语句抽取的槽位（category、budget、brand、scenario 等） |
| confidence | double | 置信度 [0, 1] |

**示例：**
```json
{
  "intent": "PRODUCT_RECOMMENDATION",
  "slots": { "category": "跑鞋", "budget": 500, "scenario": null },
  "confidence": 0.91
}
```

---

## 3. ClarifyResult — 澄清决策输出

**定义位置：** ClarifyAgent 输出

| 字段 | 类型 | 说明 |
|------|------|------|
| action | ClarifyAction | 枚举：`ASK`（继续追问）/ `READY`（槽位完整，可进推荐） |
| questionToAsk | String | action=ASK 时，要问用户的问题；action=READY 时为 null |
| missingSlots | List<String> | 尚未填满的槽位名称列表 |

**示例：**
```json
{
  "action": "ASK",
  "questionToAsk": "平时跑塑胶跑道、水泥路、还是都有？",
  "missingSlots": ["scenario"]
}
```

---

## 4. RecommendedItem — 推荐候选项

**定义位置：** RecAgent 输出中的单个商品

| 字段 | 类型 | 说明 |
|------|------|------|
| productId | long | 商品 ID |
| name | String | 商品名称 |
| price | BigDecimal | 售价 |
| reason | String | 推荐理由（给用户看的口语化解释） |
| matchScore | double | 匹配度 [0, 1] |
| attributes | Map<String, Object> | 商品属性快照（cushion、weight 等） |

**示例：**
```json
{
  "productId": 8821,
  "name": "Asics GEL-Contend 9",
  "price": 479.00,
  "reason": "GEL 缓震 + 宽鞋楦，适合你膝盖不太好的情况",
  "matchScore": 0.88,
  "attributes": { "cushion": "high", "weight": "medium" }
}
```

---

## 5. RecommendResult — 推荐 Agent 输出

**定义位置：** RecAgent 输出

| 字段 | 类型 | 说明 |
|------|------|------|
| items | List<RecommendedItem> | 推荐商品列表（已按 matchScore 排序） |
| explanationTone | String | 话术风格标记（professional / casual / caring），传给 SentimentAgent |

**示例：**
```json
{
  "items": [ { ... }, { ... }, { ... } ],
  "explanationTone": "professional"
}
```

---

## 6. EmotionResult — 情感应答输出

**定义位置：** SentimentAgent 输出

| 字段 | 类型 | 说明 |
|------|------|------|
| speechText | String | 口播话术（带情感色彩，直接送 TTS） |
| displayBlocks | List<DisplayBlock> | 前端展示卡片列表 |

**DisplayBlock 子结构：**

| 字段 | 类型 | 说明 |
|------|------|------|
| type | String | 卡片类型（product_card / text_card / compare_table） |
| data | Map<String, Object> | 卡片数据（商品信息、对比表格等） |

**示例：**
```json
{
  "speechText": "好，给你挑了三款缓震很出色的……",
  "displayBlocks": [
    { "type": "product_card", "data": { "productId": 8821, "name": "...", "price": 479 } }
  ]
}
```

---

## 7. UserProfileSnapshot — 用户画像快照

**定义位置：** UserProfileService 输出（合并 static + dynamic）

只读快照，供所有 Agent 做个性化推荐。由 `UserProfileService.load(userId)` 组装。

| 字段 | 类型 | 来源 | 说明 |
|------|------|------|------|
| userId | long | app_user | |
| gender | String | user_profile_static | MALE / FEMALE / OTHER / UNSPECIFIED |
| ageRange | String | user_profile_static | 如 "25-34" |
| city | String | user_profile_static | |
| bodyHeight | BigDecimal | user_profile_static | 身高(cm) |
| bodyWeight | BigDecimal | user_profile_static | 体重(kg) |
| shoeSize | String | user_profile_static | 鞋码 |
| skinType | String | user_profile_static | 肤质 |
| techFamiliarity | String | user_profile_static | 数码熟悉度 LOW/MEDIUM/HIGH |
| spendingRange | String | user_profile_static | 消费区间 |
| categoryPrefs | Map<String, Double> | user_profile_dynamic | 品类偏好 {"跑鞋":0.9} |
| brandPrefs | Map<String, Double> | user_profile_dynamic | 品牌偏好 {"Nike":0.8} |
| priceSensitivity | String | user_profile_dynamic | 价格敏感度 LOW/MEDIUM/HIGH |
| avgOrderAmount | BigDecimal | user_profile_dynamic | 平均客单价 |
| purchaseCount | int | user_profile_dynamic | 累计购买次数 |

---

## DTO 与 Agent 的关系

```
IntentAgent  →  IntentResult(intent, slots, confidence)
                    ↓
ClarifyAgent →  ClarifyResult(action, questionToAsk, missingSlots)
                    ↓
RecAgent     →  RecommendResult(items<RecommendedItem>, explanationTone)
                    ↓
SentimentAgent → EmotionResult(speechText, displayBlocks)
```

所有 Agent 可通过 `UserProfileSnapshot` 获取用户画像做个性化。
