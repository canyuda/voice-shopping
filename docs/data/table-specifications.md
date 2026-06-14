# 表字段规格 — 语音购物系统

## 1. merchant — 商家基础信息

| 列名 | 类型 | 可空 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| id | BIGSERIAL | 否 | 自增 | 主键 | |
| name | VARCHAR(200) | 否 | | | 商家名称 |
| contact_email | VARCHAR(320) | 是 | | | 联系邮箱 |
| contact_phone | VARCHAR(20) | 是 | | | 联系电话 |
| scale_level | VARCHAR(8) | 否 | 'NEW' | CHECK IN (HEAD/MID/SMB/NEW) | 规模等级 |
| status | VARCHAR(16) | 否 | 'ACTIVE' | CHECK IN (ACTIVE/SUSPENDED/CLOSED) | 状态 |
| settings | JSONB | 否 | '{}' | | 商家配置（LLM偏好、feature flag、品牌） |
| deleted_at | TIMESTAMPTZ | 是 | | | 软删除时间 |
| created_at | TIMESTAMPTZ | 否 | now() | | 创建时间 |
| updated_at | TIMESTAMPTZ | 否 | now() | 触发器自动更新 | 更新时间 |

**索引：**

| 名称 | 类型 | 列 | 条件 |
|------|------|----|------|
| idx_merchant_status | B-tree | status | WHERE deleted_at IS NULL |
| idx_merchant_scale_level | B-tree | scale_level | WHERE deleted_at IS NULL |

---

## 2. app_user — 用户基础信息

| 列名 | 类型 | 可空 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| id | BIGSERIAL | 否 | 自增 | 主键 | |
| merchant_id | BIGINT | 否 | | 关联 merchant | 租户隔离 |
| external_id | VARCHAR(100) | 是 | | | 外部系统用户ID |
| nickname | VARCHAR(100) | 是 | | | 昵称 |
| phone | VARCHAR(20) | 是 | | | 手机号 |
| avatar_url | VARCHAR(500) | 是 | | | 头像URL |
| status | VARCHAR(16) | 否 | 'ACTIVE' | CHECK IN (ACTIVE/INACTIVE/BLOCKED) | 状态 |
| last_active_at | TIMESTAMPTZ | 是 | | | 最近活跃时间 |
| deleted_at | TIMESTAMPTZ | 是 | | | 软删除时间 |
| created_at | TIMESTAMPTZ | 否 | now() | | 创建时间 |
| updated_at | TIMESTAMPTZ | 否 | now() | 触发器自动更新 | 更新时间 |

**索引：**

| 名称 | 类型 | 列 | 条件 |
|------|------|----|------|
| idx_app_user_merchant_id | B-tree | merchant_id | WHERE deleted_at IS NULL |
| uk_app_user_merchant_external | 唯一 | merchant_id, external_id | WHERE external_id IS NOT NULL AND deleted_at IS NULL |
| idx_app_user_phone | B-tree | merchant_id, phone | WHERE phone IS NOT NULL |

---

## 3. product — 商品主表

| 列名 | 类型 | 可空 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| id | BIGSERIAL | 否 | 自增 | 主键 | |
| merchant_id | BIGINT | 否 | | 关联 merchant | 租户隔离 |
| sku_code | VARCHAR(64) | 是 | | | 商家自定义SKU编码 |
| name | VARCHAR(500) | 否 | | | 商品名 |
| category_l1 | VARCHAR(100) | 否 | | | 一级品类（鞋、服、电子等） |
| category_l2 | VARCHAR(100) | 是 | | | 二级品类（跑鞋、篮球鞋等） |
| is_new_arrival | BOOLEAN | 否 | false | | 是否新品 |
| description | TEXT | 是 | | | 商品客观规格描述 |
| selling_points | TEXT | 是 | | | 商家营销卖点，向量化时给予更高权重 |
| price | NUMERIC(12,2) | 否 | | CHECK >= 0 | 售价（人民币） |
| original_price | NUMERIC(12,2) | 是 | | CHECK >= 0 | 原价（人民币） |
| image_urls | JSONB | 否 | '[]' | | 图片URL数组 |
| attributes | JSONB | 否 | '{}' | | 灵活属性 |
| status | VARCHAR(16) | 否 | 'ON_SALE' | CHECK IN (ON_SALE/OFF_SHELF/SOLD_OUT) | 状态 |
| embedding | vector(1024) | 是 | | | text-embedding-v3 语义向量 |
| embedding_text | TEXT | 是 | | | 向量源文本（便于重新向量化） |
| deleted_at | TIMESTAMPTZ | 是 | | | 软删除时间 |
| created_at | TIMESTAMPTZ | 否 | now() | | 创建时间 |
| updated_at | TIMESTAMPTZ | 否 | now() | 触发器自动更新 | 更新时间 |

**JSONB 约定：**
- `attributes`：`{"category":"跑鞋","brand":"Asics","specs":{"weight":"280g","cushion":"high"}}`
- `image_urls`：`["https://img1.jpg","https://img2.jpg"]`

**索引：**

| 名称 | 类型 | 列/表达式 | 条件 |
|------|------|-----------|------|
| idx_product_merchant_id | B-tree | merchant_id | WHERE deleted_at IS NULL |
| idx_product_attributes_gin | GIN (jsonb_path_ops) | attributes | WHERE deleted_at IS NULL |
| idx_product_embedding_hnsw | HNSW (vector_cosine_ops) | embedding | WHERE embedding IS NOT NULL AND deleted_at IS NULL |
| idx_product_status_price | B-tree | merchant_id, status, price | WHERE deleted_at IS NULL |
| idx_product_category | B-tree | merchant_id, category_l1, category_l2 | WHERE deleted_at IS NULL |
| idx_product_sku_code | B-tree | merchant_id, sku_code | WHERE sku_code IS NOT NULL AND deleted_at IS NULL |
| idx_product_name_trgm | GIN (gin_trgm_ops) | name | WHERE deleted_at IS NULL |

---

## 4. faq_entry — FAQ 知识库

| 列名 | 类型 | 可空 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| id | BIGSERIAL | 否 | 自增 | 主键 | |
| merchant_id | BIGINT | 否 | | 关联 merchant | 0=平台通用FAQ（配送、售后），>0=商家私有。检索用 `merchant_id IN (0, :currentMerchant)` |
| question | TEXT | 否 | | | 问题 |
| answer | TEXT | 否 | | | 答案 |
| category | VARCHAR(100) | 是 | | | FAQ分类（物流、退换、支付等） |
| tags | JSONB | 否 | '[]' | | 标签数组 |
| frequency | INT | 否 | 0 | | 命中计数（热门排序） |
| embedding | vector(1024) | 是 | | | 语义向量 |
| embedding_text | TEXT | 是 | | | 向量源文本 |
| created_at | TIMESTAMPTZ | 否 | now() | | 创建时间 |
| updated_at | TIMESTAMPTZ | 否 | now() | 触发器自动更新 | 更新时间 |

**索引：**

| 名称 | 类型 | 列/表达式 | 条件 |
|------|------|-----------|------|
| idx_faq_merchant_id | B-tree | merchant_id | |
| idx_faq_category | B-tree | merchant_id, category | |
| idx_faq_tags_gin | GIN (jsonb_path_ops) | tags | |
| idx_faq_embedding_hnsw | HNSW (vector_cosine_ops) | embedding | WHERE embedding IS NOT NULL |
| idx_faq_frequency | B-tree | merchant_id, frequency DESC | |

**使用约定：**
- `answer` 直接存成品答案，不走 LLM 改写——高频问题要可控、可审计，不让模型乱发挥。
- 向量检索相似度阈值 ≥ 0.75 才算命中，低于阈值宁可走 LLM 兜底也别答错。

## 5. user_profile_static — 静态画像

**为什么拆两张画像表：**
- **缓存策略不同：** 静态画像基本不变，缓存 TTL 可以很长；动态画像天天变，TTL 短。
- **权限粒度不同：** 静态画像含强隐私字段（身高、体重、肤质），动态画像主要是弱隐私的行为偏好（品类偏好、品牌偏好），分表便于按粒度控制访问权限。

| 列名 | 类型 | 可空 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| id | BIGSERIAL | 否 | 自增 | 主键 | |
| user_id | BIGINT | 否 | | 关联 app_user, UNIQUE | 与用户 1:1 |
| merchant_id | BIGINT | 否 | | 关联 merchant | 租户隔离 |
| gender | VARCHAR(10) | 是 | | CHECK IN (MALE/FEMALE/OTHER/UNSPECIFIED) | 性别 |
| age_range | VARCHAR(10) | 是 | | CHECK IN (18-24/25-34/35-44/45-54/55-64/65+) | 年龄段 |
| city | VARCHAR(100) | 是 | | | 城市 |
| body_height | NUMERIC(5,1) | 是 | | CHECK (0, 300) | 身高(cm) |
| body_weight | NUMERIC(5,1) | 是 | | CHECK (0, 500) | 体重(kg) |
| shoe_size | VARCHAR(20) | 是 | | | 鞋码 |
| clothing_size | VARCHAR(20) | 是 | | | 衣服尺码 |
| skin_type | VARCHAR(16) | 是 | | CHECK IN (OILY/DRY/COMBINATION/SENSITIVE/NORMAL/UNSPECIFIED) | 肤质类型 |
| tech_familiarity | VARCHAR(10) | 是 | | CHECK IN (LOW/MEDIUM/HIGH) | 数码产品熟悉度 |
| spending_range | VARCHAR(20) | 是 | | CHECK IN (0-100/100-300/300-500/500-1000/1000+) | 常态消费区间（元） |
| extra | JSONB | 否 | '{}' | | 扩展字段 |
| created_at | TIMESTAMPTZ | 否 | now() | | 创建时间 |
| updated_at | TIMESTAMPTZ | 否 | now() | 触发器自动更新 | 更新时间 |

---

## 6. user_profile_dynamic — 动态画像

| 列名 | 类型 | 可空 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| id | BIGSERIAL | 否 | 自增 | 主键 | |
| user_id | BIGINT | 否 | | 关联 app_user, UNIQUE | 与用户 1:1 |
| merchant_id | BIGINT | 否 | | 关联 merchant | 租户隔离 |
| category_prefs | JSONB | 否 | '{}' | | 品类偏好评分，如 `{"跑鞋":0.9,"篮球鞋":0.7}` |
| brand_prefs | JSONB | 否 | '{}' | | 品牌偏好评分，如 `{"Nike":0.8,"Asics":0.9}` |
| price_sensitivity | VARCHAR(10) | 是 | 'MEDIUM' | CHECK IN (LOW/MEDIUM/HIGH) | 价格敏感度 |
| recent_behavior | JSONB | 否 | '[]' | | 最近行为数组 |
| purchase_count | INT | 否 | 0 | | 累计购买次数 |
| avg_order_amount | NUMERIC(12,2) | 是 | | CHECK >= 0 | 平均客单价（元），由用户行为每日计算更新 |
| last_purchase_at | TIMESTAMPTZ | 是 | | | 最近购买时间 |
| created_at | TIMESTAMPTZ | 否 | now() | | 创建时间 |
| updated_at | TIMESTAMPTZ | 否 | now() | 触发器自动更新 | 更新时间 |

**JSONB 约定：**
- `recent_behavior`：`[{"action":"view","productId":8821,"ts":"2026-06-11T10:00:00Z"}]`

---

## 7. session — 会话元数据

| 列名 | 类型 | 可空 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| id | UUID | 否 | gen_random_uuid() | 主键 | |
| merchant_id | BIGINT | 是 | | 关联 merchant | 租户隔离（平台入口可为 NULL，商家入口必填） |
| user_id | BIGINT | 否 | | 关联 app_user | 发起用户 |
| channel | VARCHAR(25) | 否 | 'HOME_ENTRY' | CHECK IN (HOME_ENTRY/PRODUCT_PAGE/SEARCH_FALLBACK) | 入口场景 |
| outcome | VARCHAR(20) | 是 | | CHECK IN (ORDERED/ABANDONED/FOLLOWUP) | 会话结局；null 表示未结束 |
| total_tokens | INT | 否 | 0 | | 消耗 LLM token 数，用于成本统计 |
| bound_product_id | BIGINT | 是 | | 关联 product | 入口绑定的商品（PRODUCT_PAGE 场景） |
| started_at | TIMESTAMPTZ | 否 | now() | | 开始时间 |
| ended_at | TIMESTAMPTZ | 是 | | | 结束时间 |
| created_at | TIMESTAMPTZ | 否 | now() | | 创建时间 |
| updated_at | TIMESTAMPTZ | 否 | now() | 触发器自动更新 | 更新时间 |

---

## 8. session_message — 消息历史

| 列名 | 类型 | 可空 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| id | BIGSERIAL | 否 | 自增 | 主键 | |
| session_id | UUID | 否 | | 关联 session | 所属会话 |
| merchant_id | BIGINT | 否 | | 关联 merchant | 租户隔离 |
| role | VARCHAR(16) | 否 | | CHECK IN (USER/ASSISTANT/SYSTEM) | 角色 |
| turn | INT | 否 | | | 对话轮次编号（从1开始递增） |
| agent_name | VARCHAR(32) | 是 | | | 生成此消息的 Agent（IntentAgent/ClarifyAgent/RecAgent/EmotionAgent） |
| content | TEXT | 否 | | | 消息文本内容 |
| content_audio_url | VARCHAR(500) | 是 | | | TTS 生成的音频文件 URL |
| intent | VARCHAR(32) | 是 | | | 该轮识别的意图 |
| tokens | INT | 否 | 0 | | 本轮消耗的 LLM token 数 |
| metadata | JSONB | 否 | '{}' | | 消息级元数据（ASR置信度、TTS参数等） |
| created_at | TIMESTAMPTZ | 否 | now() | | 创建时间（仅追加，无 updated_at） |

---

## 9. session_state — 会话状态机

| 列名 | 类型 | 可空 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| id | UUID | 否 | | 主键, 关联 session | 与 session 1:1 共享主键 |
| merchant_id | BIGINT | 否 | | 关联 merchant | 租户隔离 |
| phase | VARCHAR(32) | 否 | 'INTENT' | CHECK IN (INTENT/CLARIFY/RECOMMEND/ORDER_CONFIRM/ENDED) | 状态机阶段 |
| current_intent | VARCHAR(32) | 是 | | | 当前识别的意图 |
| slots | JSONB | 否 | '{}' | | 累积槽位，如 `{"category":"跑鞋","budget":500}` |
| pending_ask | TEXT | 是 | | | ClarifyAgent 待问问题 |
| turn_count | INT | 否 | 0 | | 当前阶段轮次 |
| last_recommendations | JSONB | 是 | | | 最近一次 RecAgent 推荐结果 |
| created_at | TIMESTAMPTZ | 否 | now() | | 创建时间 |
| updated_at | TIMESTAMPTZ | 否 | now() | 触发器自动更新 | 更新时间 |

---

## 10. order_record — 订单主表

| 列名 | 类型 | 可空 | 默认值 | 约束 | 说明 |
|------|------|------|--------|------|------|
| id | BIGSERIAL | 否 | 自增 | 主键 | |
| merchant_id | BIGINT | 否 | | 关联 merchant | 租户隔离 |
| user_id | BIGINT | 否 | | 关联 app_user | 下单用户 |
| session_id | UUID | 是 | | 关联 session | 关联会话 |
| order_no | VARCHAR(64) | 否 | | 商户内唯一 | 业务订单号 |
| items | JSONB | 否 | | | 商品列表快照 |
| total_amount | NUMERIC(12,2) | 否 | | CHECK >= 0 | 总金额（人民币） |
| status | VARCHAR(16) | 否 | 'CREATED' | CHECK IN (CREATED/PAID/SHIPPED/DELIVERED/CANCELLED/REFUNDED) | 订单状态 |
| agent_attribution | BOOLEAN | 否 | false | | AI 导购归因标记，true=AI推荐促成的订单 |
| source_intent | VARCHAR(32) | 是 | | | 触发下单的意图（AI归因） |
| ai_context | JSONB | 是 | | | AI决策上下文快照 |
| receiver_name | VARCHAR(100) | 是 | | | 收件人姓名（demo 明文，正式环境按隐私规范处理） |
| receiver_phone | VARCHAR(20) | 是 | | | 收件人电话（demo 明文，正式环境按隐私规范处理） |
| receiver_addr | TEXT | 是 | | | 收件人地址（demo 明文，正式环境按隐私规范处理） |
| created_at | TIMESTAMPTZ | 否 | now() | | 创建时间 |
| updated_at | TIMESTAMPTZ | 否 | now() | 触发器自动更新 | 更新时间 |

**JSONB 约定：**
- `items`：`[{"productId":8821,"name":"Asics GEL-Contend 9","price":479,"quantity":1}]`（下单时快照，不受商品后续变动影响）
- `ai_context`：`{"recAgentScore":0.88,"clarifyRounds":2,"slots":{"category":"跑鞋","budget":500}}`

**索引：**

| 名称 | 类型 | 列 | 条件 |
|------|------|----|------|
| uk_order_record_order_no | 唯一 | merchant_id, order_no | |
| idx_order_record_merchant_user | B-tree | merchant_id, user_id | 商家查自己的订单 |
| idx_order_record_user_created | B-tree | user_id, created_at DESC | 用户查自己的订单历史 |
| idx_order_record_session_id | B-tree | session_id | WHERE session_id IS NOT NULL |
| idx_order_record_status | B-tree | merchant_id, status | |
| idx_order_record_created_at | B-tree | merchant_id, created_at DESC | |

---

## Redis Key 规格

| Key 模式 | 类型 | TTL | 说明 |
|----------|------|-----|------|
| `vs:session:{sessionId}` | Hash | 30min | 会话状态快路径（session_state 表的 Redis 镜像） |
| `vs:short_memory:{sessionId}` | List | 30min | 最近10轮对话摘要 |
| `vs:user:profile:{userId}` | Hash | 24h | 用户画像缓存（static + dynamic 合并） |
| `vs:intent_cache:{userId}:{hash}` | String | 5min | 意图识别缓存（避免重复调用 LLM） |
| `vs:rec_cache:{hash}` | String | 10min | 推荐结果缓存（避免重复向量化 + 排序） |
