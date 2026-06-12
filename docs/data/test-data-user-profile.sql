-- ============================================================
-- Test data: app_user + user_profile_static + user_profile_dynamic (5 users)
-- Covers: full profile, partial profile, cold-start (dynamic only),
--          high spender, budget-conscious
-- merchant_id = 1: primary merchant for testing
-- ============================================================

-- app_user
INSERT INTO app_user (id, merchant_id, external_id, nickname, phone, status) VALUES
(101, 1, 'wx_oABC123', '跑鞋达人小李', '13800001001', 'ACTIVE'),
(102, 1, 'wx_oDEF456', '篮球少年阿明', '13800001002', 'ACTIVE'),
(103, 1, 'wx_oGHI789', '养生跑者老王', '13800001003', 'ACTIVE'),
(104, 1, 'wx_oJKL012', '潮鞋控小美', '13800001004', 'ACTIVE'),
(105, 1, 'wx_oMNO345', '学生党小张', '13800001005', 'ACTIVE');

-- user_profile_static
INSERT INTO user_profile_static (user_id, merchant_id, gender, age_range, city, body_height, body_weight,
                                  shoe_size, clothing_size, skin_type, tech_familiarity, spending_range) VALUES
-- 101: 跑鞋达人 — 完整画像
(101, 1, 'MALE', '25-34', '上海', 175.0, 72.0, '42', 'L', 'NORMAL', 'HIGH', '500-1000'),
-- 102: 篮球少年 — 完整画像
(102, 1, 'MALE', '18-24', '北京', 183.0, 80.0, '44', 'XL', 'OILY', 'HIGH', '300-500'),
-- 103: 养生跑者 — 部分字段为空
(103, 1, 'MALE', '45-54', '成都', 170.0, 68.0, '41', NULL, 'DRY', 'MEDIUM', '100-300'),
-- 104: 潮鞋控 — 完整画像
(104, 1, 'FEMALE', '25-34', '深圳', 163.0, 52.0, '37', 'S', 'SENSITIVE', 'HIGH', '1000+'),
-- 105: 学生党 — 低消费
(105, 1, 'MALE', '18-24', '广州', 178.0, 70.0, '43', 'M', 'COMBINATION', 'MEDIUM', '0-100');

-- user_profile_dynamic
-- NOTE: Each row uses a single JSON string (no || concat) to preserve jsonb type.
INSERT INTO user_profile_dynamic (user_id, merchant_id, category_prefs, brand_prefs, price_sensitivity,
                                   recent_behavior, purchase_count, avg_order_amount, last_purchase_at) OVERRIDING SYSTEM VALUE VALUES
-- 101: 跑鞋达人 — 高跑鞋偏好，Asics忠实用户
(101, 1,
 '{"跑鞋": 0.92, "篮球鞋": 0.45, "休闲鞋": 0.30}',
 '{"Asics": 0.95, "HOKA": 0.75, "Nike": 0.50}',
 'MEDIUM',
 '[{"action":"purchase","category":"跑鞋","brand":"Asics","amount":479,"ts":"2026-06-10T14:30:00Z"},{"action":"view","category":"跑鞋","brand":"HOKA","ts":"2026-06-11T09:15:00Z"},{"action":"view","category":"跑鞋","brand":"Nike","ts":"2026-06-11T10:00:00Z"}]'::jsonb,
 3, 556.67, '2026-06-10T14:30:00Z'),

-- 102: 篮球少年 — 篮球鞋主导，Nike/Jordan粉
(102, 1,
 '{"篮球鞋": 0.90, "跑鞋": 0.35, "拖鞋": 0.20}',
 '{"Nike": 0.88, "Jordan": 0.80, "Adidas": 0.40}',
 'LOW',
 '[{"action":"purchase","category":"篮球鞋","brand":"Nike","amount":1299,"ts":"2026-06-08T20:00:00Z"},{"action":"view","category":"篮球鞋","brand":"Jordan","ts":"2026-06-09T18:30:00Z"}]'::jsonb,
 2, 1049.50, '2026-06-08T20:00:00Z'),

-- 103: 养生跑者 — 价格敏感，偏缓震
(103, 1,
 '{"跑鞋": 0.70, "健步鞋": 0.50}',
 '{"Asics": 0.60, "李宁": 0.45}',
 'HIGH',
 '[{"action":"view","category":"跑鞋","brand":"Asics","ts":"2026-06-11T08:00:00Z"},{"action":"view","category":"健步鞋","brand":"李宁","ts":"2026-06-11T09:30:00Z"}]'::jsonb,
 1, 299.00, '2026-06-05T16:00:00Z'),

-- 104: 潮鞋控 — 高消费，多品类
(104, 1,
 '{"休闲鞋": 0.85, "跑鞋": 0.60, "板鞋": 0.75}',
 '{"Nike": 0.70, "Adidas": 0.65, "New Balance": 0.80}',
 'LOW',
 '[{"action":"purchase","category":"板鞋","brand":"New Balance","amount":899,"ts":"2026-06-09T15:00:00Z"},{"action":"purchase","category":"休闲鞋","brand":"Adidas","amount":799,"ts":"2026-06-10T11:00:00Z"},{"action":"view","category":"跑鞋","brand":"Nike","ts":"2026-06-11T14:00:00Z"}]'::jsonb,
 5, 856.00, '2026-06-10T11:00:00Z'),

-- 105: 学生党 — 冷启动，极少行为
(105, 1,
 '{"跑鞋": 0.10}',
 '{}',
 'HIGH',
 '[]'::jsonb,
 0, NULL, NULL);
