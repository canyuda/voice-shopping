-- ============================================================
-- Test data: product (10 rows)
-- Covers test cases: 标准导购链路(#1), 冷启动(#4), 多商家隔离(#5)
-- merchant_id = 1: primary merchant for testing
-- All products are shoes (category_l1 = '鞋类') for test case consistency
-- ============================================================

INSERT INTO product (merchant_id, sku_code, name, category_l1, category_l2, is_new_arrival,
                     description, selling_points, price, original_price,
                     image_urls, attributes, status, embedding_text) VALUES

-- 1. Running shoe within budget (test case #1: budget 1000)
(1, 'SHOES-HOKA-001', 'HOKA Clifton 9', '鞋类', '跑鞋', false,
 '中底采用压缩EVA泡棉，Meta-Rocker弧形鞋底设计，工程网布鞋面透气轻盈，重量约240g（男款US 9）。',
 '轻盈缓震长距离跑鞋，适合日常慢跑和LSD训练，膝盖友好型选手首选。',
 899.00, 1099.00,
 '["https://img.example.com/hoka-clifton9-1.jpg", "https://img.example.com/hoka-clifton9-2.jpg"]',
 '{"brand": "HOKA", "weight": "240g", "cushion": "high", "drop": "5mm", "surface": ["road", "track"], "suitable_for": ["daily_running", "LSD"]}',
 'ON_SALE', 'HOKA Clifton 9 跑鞋 轻盈缓震 长距离 膝盖友好'),

-- 2. Running shoe within budget (test case #1)
(1, 'SHOES-NIKE-001', 'Nike Pegasus 40', '鞋类', '跑鞋', false,
 'React泡棉中底搭配Zoom Air气垫单元，Flywire飞线动态包裹，重量约285g（男款US 9）。',
 '经典日常训练鞋，React+Zoom Air双密度缓震，适配多种路面，性价比之选。',
 799.00, 899.00,
 '["https://img.example.com/nike-peg40-1.jpg", "https://img.example.com/nike-peg40-2.jpg"]',
 '{"brand": "Nike", "weight": "285g", "cushion": "medium", "drop": "10mm", "surface": ["road", "track", "concrete"], "suitable_for": ["daily_running", "tempo"]}',
 'ON_SALE', 'Nike Pegasus 40 跑鞋 日常训练 React缓震 性价比'),

-- 3. Running shoe within budget, new arrival (test case #4: cold start)
(1, 'SHOES-ASICS-001', 'Asics GEL-Contend 9', '鞋类', '跑鞋', true,
 'AMPLIFOAM中底+后跟GEL缓震胶，透气网布鞋面，重量约290g（男款US 9），宽鞋楦设计。',
 '入门级缓震跑鞋，GEL胶吸震护膝，宽鞋楦不挤脚，新手和膝盖不适人群的安心之选。',
 479.00, 599.00,
 '["https://img.example.com/asics-contend9-1.jpg", "https://img.example.com/asics-contend9-2.jpg"]',
 '{"brand": "Asics", "weight": "290g", "cushion": "high", "drop": "10mm", "surface": ["road", "track"], "suitable_for": ["beginner", "daily_running"], "width": "wide"}',
 'ON_SALE', 'Asics GEL-Contend 9 跑鞋 入门缓震 GEL吸震 宽鞋楦 膝盖友好'),

-- 4. Running shoe, new arrival (test case #4: cold start new arrival boost)
(1, 'SHOES-ADIDAS-001', 'Adidas Ultraboost Light', '鞋类', '跑鞋', true,
 'Light BOOST中底科技，Primeknit+编织鞋面，Continental橡胶外底，重量约310g（男款US 9）。',
 '轻量化Boost回弹，编织鞋面如袜子般贴合，马牌橡胶抓地力强，潮跑两不误。',
 999.00, 1299.00,
 '["https://img.example.com/adidas-ublight-1.jpg", "https://img.example.com/adidas-ublight-2.jpg"]',
 '{"brand": "Adidas", "weight": "310g", "cushion": "high", "drop": "10mm", "surface": ["road", "concrete"], "suitable_for": ["daily_running", "casual"]}',
 'ON_SALE', 'Adidas Ultraboost Light 跑鞋 Boost回弹 编织鞋面 抓地力'),

-- 5. Running shoe within budget (test case #1: additional option)
(1, 'SHOES-SAUCONY-001', 'Saucony Ride 16', '鞋类', '跑鞋', false,
 'PWRRUN+泡棉中底，FORMFIT贴合系统，工程网布鞋面，重量约245g（男款US 9）。',
 '次顶级缓震训练鞋，PWRRUN+回弹出色，轻量包裹好，节奏跑和日常训练利器。',
 769.00, 899.00,
 '["https://img.example.com/saucony-ride16-1.jpg", "https://img.example.com/saucony-ride16-2.jpg"]',
 '{"brand": "Saucony", "weight": "245g", "cushion": "medium_high", "drop": "8mm", "surface": ["road", "track"], "suitable_for": ["daily_running", "tempo"]}',
 'ON_SALE', 'Saucony Ride 16 跑鞋 次顶级缓震 PWRRUN 轻量'),

-- 6. Running shoe over budget for comparison
(1, 'SHOES-NB-001', 'New Balance FuelCell 1080v13', '鞋类', '跑鞋', false,
 'FuelCell泡棉中底，Hypoknit上层+工程网布下层，重量约285g（男款US 9）。',
 '顶级缓震旗舰，FuelCell能量反馈饱满，适合长距离慢跑，脚感"踩云"级别。',
 1199.00, 1399.00,
 '["https://img.example.com/nb-1080v13-1.jpg", "https://img.example.com/nb-1080v13-2.jpg"]',
 '{"brand": "New Balance", "weight": "285g", "cushion": "very_high", "drop": "6mm", "surface": ["road"], "suitable_for": ["daily_running", "LSD", "recovery"]}',
 'ON_SALE', 'New Balance FuelCell 1080v13 跑鞋 顶级缓震 踩云脚感 长距离'),

-- 7. Basketball shoe (different category for intent testing)
(1, 'SHOES-NIKE-002', 'Nike Air Zoom G.T. Cut 3', '鞋类', '篮球鞋', true,
 'ZoomX泡棉+React鞋垫，低帮设计灵活性高，重量约340g（男款US 9）。',
 '实战篮球鞋，ZoomX回弹+React缓震双重保障，低帮灵活变向，突破型后卫首选。',
 1099.00, 1299.00,
 '["https://img.example.com/nike-gtcut3-1.jpg", "https://img.example.com/nike-gtcut3-2.jpg"]',
 '{"brand": "Nike", "weight": "340g", "cushion": "high", "cut": "low", "suitable_for": ["basketball", "guard"]}',
 'ON_SALE', 'Nike Air Zoom G.T. Cut 3 篮球鞋 实战 回弹 低帮 后卫'),

-- 8. Casual shoe (for category diversity)
(1, 'SHOES-NIKE-003', 'Nike Air Force 1 \'07', '鞋类', '休闲鞋', false,
 '全粒面皮革鞋面，Air Sole气垫，橡胶杯底，重量约370g（男款US 9）。',
 '经典百搭小白鞋，全皮鞋面质感在线，Air Sole缓震舒适，日常穿搭永不过时。',
 699.00, 799.00,
 '["https://img.example.com/nike-af1-1.jpg", "https://img.example.com/nike-af1-2.jpg"]',
 '{"brand": "Nike", "weight": "370g", "cushion": "low", "suitable_for": ["casual", "lifestyle"]}',
 'ON_SALE', 'Nike Air Force 1 07 休闲鞋 经典百搭 小白鞋 全皮'),

-- 9. Running shoe for merchant 2 (test case #5: multi-tenant isolation)
(2, 'SHOES-LJN-001', '李宁飞电4 Elite', '鞋类', '跑鞋', true,
 '䨻科技中底+碳板，GCU全天候橡胶外底，重量约215g（男款US 9）。',
 '国产碳板竞速跑鞋，䨻中底+碳板推进力强，适合进阶跑者冲击PB。',
 1099.00, 1299.00,
 '["https://img.example.com/lining-feidian4-1.jpg", "https://img.example.com/lining-feidian4-2.jpg"]',
 '{"brand": "李宁", "weight": "215g", "cushion": "medium", "drop": "4mm", "surface": ["road", "track"], "suitable_for": ["racing", "tempo"], "plate": "carbon"}',
 'ON_SALE', '李宁飞电4 Elite 跑鞋 碳板竞速 䨻科技 冲击PB'),

-- 10. Running shoe for merchant 2 (test case #5: multi-tenant isolation)
(2, 'SHOES-ANTA-001', '安踏 C202 GT', '鞋类', '跑鞋', false,
 '氮科技中底+3D仿生碳板，Space Fiber鞋面，重量约220g（男款US 9）。',
 '碳板竞速跑鞋，氮科技轻弹+碳板推进，专业马拉松比赛鞋，助力突破自我。',
 899.00, 1099.00,
 '["https://img.example.com/anta-c202gt-1.jpg", "https://img.example.com/anta-c202gt-2.jpg"]',
 '{"brand": "安踏", "weight": "220g", "cushion": "medium", "drop": "6mm", "surface": ["road"], "suitable_for": ["racing", "marathon"], "plate": "carbon"}',
 'ON_SALE', '安踏 C202 GT 跑鞋 碳板竞速 氮科技 马拉松');
