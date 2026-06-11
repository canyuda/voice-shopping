# 集成测试用例

## 1. 标准导购链路

**场景：** 用户发起商品推荐请求，走完 IntentAgent → ClarifyAgent → RecAgent → SentimentAgent 全链路。

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 用户 Alice 登录商家 1 | 登录成功，获取 token |
| 2 | 创建会话（channel=HOME_ENTRY） | 返回 session_id |
| 3 | 发送语音/文本："帮我找一双跑鞋，一千以内" | IntentAgent 返回 `{intent: PRODUCT_RECOMMENDATION, slots: {category: "跑鞋", budget: 1000}, confidence ≥ 0.8}` |
| 4 | ClarifyAgent 判断槽位 | `{action: ASK, questionToAsk: "平时跑什么路面？", missingSlots: ["scenario"]}` |
| 5 | 用户回答："塑胶跑道" | ClarifyAgent 返回 `{action: READY}` |
| 6 | RecAgent 推荐 | 返回 2-3 个商品，如 HOKA Clifton 9、Nike Pegasus 40，matchScore 均 > 0.7 |
| 7 | SentimentAgent 包装口播 | speechText 包含推荐理由，displayBlocks 包含商品卡片 |

**断言要点：**
- IntentAgent confidence ≥ 0.8
- ClarifyAgent 至少追问一轮
- RecAgent 返回商品均在 budget 范围内
- SentimentAgent speechText 非空且包含推荐商品名

---

## 2. FAQ 命中

**场景：** 用户问高频问题，直接命中 faq_entry，不走 LLM 对话链路。

**前置数据：**
- faq_entry 表中 merchant_id=0 存入 `{"question": "多久能收到货", "answer": "下单后1-3个工作日发货，3-5个工作日送达", "category": "shipping"}`
- 已生成 embedding

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 用户问："多久发货" | IntentAgent 返回 `{intent: CHITCHAT}` 或 FAQ 匹配拦截 |
| 2 | 向量检索 faq_entry | 相似度 ≥ 0.75，命中 "多久能收到货" |
| 3 | 返回答案 | 直接返回 faq_entry.answer 原文："下单后1-3个工作日发货，3-5个工作日送达"，**不经 LLM 改写** |
| 4 | frequency +1 | faq_entry.frequency 更新 |

**断言要点：**
- 向量相似度 ≥ 0.75
- 返回的是 faq_entry.answer 原文，非 LLM 生成
- faq_entry.frequency 递增
- 全程不调用对话 LLM（qwen-max），节省 token

---

## 3. 越权兜底

**场景：** 用户请求超出业务范围，系统礼貌拒绝。

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | 用户问："帮我写一份周报" | IntentAgent 返回 `{intent: OUT_OF_SCOPE, confidence ≥ 0.8}` |
| 2 | SentimentAgent 生成拒绝话术 | speechText 包含礼貌拒绝（如"我主要帮你选购商品，写周报这件事暂时帮不上忙"） |
| 3 | displayBlocks 为空 | 不展示任何商品卡片 |

**断言要点：**
- intent 必须是 OUT_OF_SCOPE，不能误判为 CHITCHAT
- 不调用 ClarifyAgent、RecAgent
- speechText 语气礼貌，不含商品信息

---

## 4. 冷启动

**场景：** 新用户无动态画像，系统通过追问补全信息，最终给出新品推荐。

**前置数据：**
- 用户 David 刚注册，user_profile_dynamic 全空
- product 表中有 `is_new_arrival = true` 的跑鞋

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | David 登录 | user_profile_dynamic 为空或全默认值 |
| 2 | David 问："有啥好鞋推荐" | IntentAgent 返回 PRODUCT_RECOMMENDATION，但 slots 基本为空 |
| 3 | ClarifyAgent 补问 | 追问品类、预算、用途等 |
| 4 | David 逐步回答 | ClarifyAgent 返回 READY |
| 5 | RecAgent 推荐 | 因为无历史偏好，推荐结果中 `is_new_arrival = true` 的商品占比更高 |

**断言要点：**
- ClarifyAgent 至少追问 2 轮（冷启动槽位更空）
- 推荐结果包含 is_new_arrival=true 的商品
- user_profile_dynamic 在推荐后被更新（至少 category_prefs 不为空）

---

## 5. 多商家隔离

**场景：** 验证不同商家的数据严格隔离，看不到别家的用户和商品。

**前置数据：**
- 商家 1：有跑鞋商品 A、用户 Bob
- 商家 2：有跑鞋商品 B、用户 Carol

| 步骤 | 操作 | 预期 |
|------|------|------|
| 1 | Bob（商家 1）搜索跑鞋 | 只返回商家 1 的商品 A，看不到商品 B |
| 2 | Bob 查看订单列表 | 只有自己在商家 1 下的订单，看不到 Carol 的 |
| 3 | Bob 直接请求商家 2 的商品 B 接口 | 返回 403 或空结果 |
| 4 | Carol（商家 2）搜索跑鞋 | 只返回商家 2 的商品 B |

**断言要点：**
- 商品搜索 SQL 自动注入 `WHERE merchant_id = 1`（Bob）/ `merchant_id = 2`（Carol）
- 订单查询同理
- 跨商家访问返回 403，不是 500
- MyBatis-Plus 租户插件正常拦截
