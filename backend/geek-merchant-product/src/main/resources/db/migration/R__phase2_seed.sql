-- Brand system demo seed: platform / self-operated / GEEK SELECT / flagship / third-party
-- Keep sku 11001 for trade/seckill compatibility (merchant 2001).
-- Cover/logo images: local mall-web JPEG under /covers/geek-spu-*.jpg (see public/covers/ATTRIBUTION.md).
-- Run scripts/apply-local-covers.ps1 after demo-up if DB still points at old remotes.

INSERT INTO merchant(id,merchant_type,company_name,credit_code,status,created_at,updated_at)
SELECT 2000,'SELF_OPERATED','极客商城自营','SELF-GEEK-0001','APPROVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM merchant WHERE id=2000);
INSERT INTO merchant(id,merchant_type,company_name,credit_code,status,created_at,updated_at)
SELECT 2001,'THIRD_PARTY','Nova 智能终端','91310000NOVA2001','APPROVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM merchant WHERE id=2001);
INSERT INTO merchant(id,merchant_type,company_name,credit_code,status,created_at,updated_at)
SELECT 2002,'THIRD_PARTY','未来数码科技','91310000FUTURE2002','APPROVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM merchant WHERE id=2002);
INSERT INTO merchant(id,merchant_type,company_name,credit_code,status,created_at,updated_at)
SELECT 2003,'THIRD_PARTY','Orion 终端科技','91310000ORION2003','APPROVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM merchant WHERE id=2003);

UPDATE merchant SET company_name='极客商城自营',merchant_type='SELF_OPERATED',credit_code='SELF-GEEK-0001',status='APPROVED' WHERE id=2000;
UPDATE merchant SET company_name='Nova 智能终端',merchant_type='THIRD_PARTY',credit_code='91310000NOVA2001',status='APPROVED' WHERE id=2001;
UPDATE merchant SET company_name='未来数码科技',merchant_type='THIRD_PARTY',credit_code='91310000FUTURE2002',status='APPROVED' WHERE id=2002;
UPDATE merchant SET company_name='Orion 终端科技',merchant_type='THIRD_PARTY',credit_code='91310000ORION2003',status='APPROVED' WHERE id=2003;

INSERT INTO shop(id,merchant_id,shop_name,shop_type,logo_url,announcement,status,created_at,updated_at)
SELECT 3000,2000,'GEEK 自营','SELF_OPERATED','/covers/geek-spu-10003.jpg','平台自营，多品牌正品与 GEEK SELECT 配件','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM shop WHERE id=3000);
INSERT INTO shop(id,merchant_id,shop_name,shop_type,logo_url,announcement,status,created_at,updated_at)
SELECT 3001,2001,'Nova 官方旗舰店','OFFICIAL_FLAGSHIP','/covers/geek-spu-10001.jpg','Nova 品牌官方直营','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM shop WHERE id=3001);
INSERT INTO shop(id,merchant_id,shop_name,shop_type,logo_url,announcement,status,created_at,updated_at)
SELECT 3002,2002,'未来数码专营店','THIRD_PARTY','/covers/geek-spu-10002.jpg','授权经销：笔记本、显示与外设','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM shop WHERE id=3002);
INSERT INTO shop(id,merchant_id,shop_name,shop_type,logo_url,announcement,status,created_at,updated_at)
SELECT 3003,2003,'Orion 官方旗舰店','OFFICIAL_FLAGSHIP','/covers/geek-spu-10007.jpg','Orion 折叠与创作本官方店','ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM shop WHERE id=3003);

UPDATE shop SET shop_name='GEEK 自营',shop_type='SELF_OPERATED',logo_url='/covers/geek-spu-10003.jpg',announcement='平台自营，多品牌正品与 GEEK SELECT 配件',status='ENABLED' WHERE id=3000;
UPDATE shop SET shop_name='Nova 官方旗舰店',shop_type='OFFICIAL_FLAGSHIP',logo_url='/covers/geek-spu-10001.jpg',announcement='Nova 品牌官方直营',status='ENABLED' WHERE id=3001;
UPDATE shop SET shop_name='未来数码专营店',shop_type='THIRD_PARTY',logo_url='/covers/geek-spu-10002.jpg',announcement='授权经销：笔记本、显示与外设',status='ENABLED' WHERE id=3002;
UPDATE shop SET shop_name='Orion 官方旗舰店',shop_type='OFFICIAL_FLAGSHIP',logo_url='/covers/geek-spu-10007.jpg',announcement='Orion 折叠与创作本官方店',status='ENABLED' WHERE id=3003;

INSERT INTO product_category VALUES
(100,NULL,'电脑整机',1,'/100/',10,'ENABLED'),
(110,100,'轻薄本',2,'/100/110/',10,'ENABLED'),
(120,100,'游戏本',2,'/100/120/',20,'ENABLED'),
(130,100,'商务本',2,'/100/130/',30,'ENABLED'),
(200,NULL,'智能手机',1,'/200/',20,'ENABLED'),
(210,200,'折叠屏',2,'/200/210/',10,'ENABLED'),
(220,200,'影像旗舰',2,'/200/220/',20,'ENABLED'),
(300,NULL,'显示器',1,'/300/',30,'ENABLED'),
(400,NULL,'数码配件',1,'/400/',40,'ENABLED'),
(410,400,'充电与线材',2,'/400/410/',10,'ENABLED'),
(420,400,'键鼠外设',2,'/400/420/',20,'ENABLED'),
(430,400,'扩展坞支架',2,'/400/430/',30,'ENABLED'),
(500,NULL,'影音娱乐',1,'/500/',50,'ENABLED'),
(510,500,'耳机',2,'/500/510/',10,'ENABLED'),
(600,NULL,'智能穿戴',1,'/600/',60,'ENABLED')
ON DUPLICATE KEY UPDATE category_name=VALUES(category_name),path=VALUES(path),sort_order=VALUES(sort_order),status=VALUES(status);

UPDATE product_brand SET brand_name='ORION_RENAME_TMP' WHERE id=502 AND brand_name='NOVA';
UPDATE product_brand SET brand_name='NOVA' WHERE id=501;
UPDATE product_brand SET brand_name='ORION' WHERE id=502;
UPDATE product_brand SET brand_name='PIXELCORE' WHERE id=503;

INSERT INTO product_brand VALUES
(500,'GEEK SELECT','/covers/geek-spu-10004.jpg','平台自有数码配件品牌：充电、键鼠、扩展与收纳','ENABLED'),
(501,'NOVA','/covers/geek-spu-10008.jpg','创新移动与轻薄计算品牌','ENABLED'),
(502,'ORION','/covers/geek-spu-10003.jpg','折叠屏与高性能创作本品牌','ENABLED'),
(503,'PIXELCORE','/covers/geek-spu-10002.jpg','专业显示与影像周边品牌','ENABLED'),
(504,'AURORA','/covers/geek-spu-10009.jpg','游戏外设与装机配件品牌','ENABLED'),
(505,'NEXUS','/covers/geek-spu-10012.jpg','智能穿戴与健康数码品牌','ENABLED')
ON DUPLICATE KEY UPDATE brand_name=VALUES(brand_name),logo_url=VALUES(logo_url),description=VALUES(description),status=VALUES(status);

INSERT IGNORE INTO product_category_brand VALUES
(110,501),(110,502),(120,502),(120,504),(130,501),(210,502),(220,501),(300,503),
(410,500),(420,500),(420,504),(430,500),(510,501),(510,500),(600,505);

INSERT INTO product_attr_group VALUES (600,110,'核心配置',10),(610,110,'外观规格',20),(620,300,'显示参数',10),(630,410,'充电参数',10)
ON DUPLICATE KEY UPDATE group_name=VALUES(group_name);
INSERT INTO product_attr VALUES
(701,600,'处理器','ENUM',NULL,1,1,0),(702,600,'内存','ENUM','GB',1,1,1),(703,600,'存储','ENUM','GB',1,1,1),
(704,610,'颜色','ENUM',NULL,1,1,1),(705,620,'分辨率','ENUM',NULL,1,1,0),(706,630,'功率','ENUM','W',1,1,0)
ON DUPLICATE KEY UPDATE attr_name=VALUES(attr_name);
INSERT INTO product_attr_option VALUES
(801,702,'16GB',10),(802,702,'32GB',20),(803,703,'1TB',10),(804,704,'曜石黑',10),(805,704,'冰川银',20),(806,705,'3840×2160',10),(807,706,'100W',10)
ON DUPLICATE KEY UPDATE option_value=VALUES(option_value);

INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10001,2001,3001,'THIRD_PARTY',110,501,'NovaBook Pro 14','1.19kg 航空铝机身，全天候创作性能','APPROVED','ON_SALE',6999,7999,'/covers/geek-spu-10001.jpg',1280,4.92,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10001);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10002,2002,3002,'THIRD_PARTY',300,503,'PixelCore Creator 32 MiniLED','1152 分区背光，专业创作级 4K 显示器','APPROVED','ON_SALE',4299,4299,'/covers/geek-spu-10002.jpg',642,4.88,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10002);

UPDATE product_spu SET merchant_id=2001,shop_id=3001,source_type='THIRD_PARTY',category_id=110,brand_id=501,title='NovaBook Pro 14',subtitle='1.19kg 航空铝机身，全天候创作性能',audit_status='APPROVED',sale_status='ON_SALE',min_price=6999,max_price=7999,cover_url='/covers/geek-spu-10001.jpg' WHERE id=10001;
UPDATE product_spu SET merchant_id=2002,shop_id=3002,source_type='THIRD_PARTY',category_id=300,brand_id=503,title='PixelCore Creator 32 MiniLED',subtitle='1152 分区背光，专业创作级 4K 显示器',audit_status='APPROVED',sale_status='ON_SALE',min_price=4299,max_price=4299,cover_url='/covers/geek-spu-10002.jpg' WHERE id=10002;

INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10003,2000,3000,'SELF_OPERATED',110,502,'OrionBook Pro 16','GEEK 自营采购 · 高性能创作本','APPROVED','ON_SALE',8999,8999,'/covers/geek-spu-10003.jpg',860,4.90,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10003);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10004,2000,3000,'SELF_OPERATED',410,500,'GEEK SELECT 100W 氮化镓充电器','自有品牌 · 三口快充旅行装','APPROVED','ON_SALE',199,199,'/covers/geek-spu-10004.jpg',3200,4.95,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10004);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10005,2000,3000,'SELF_OPERATED',420,500,'GEEK SELECT 低延迟机械键盘','自有品牌 · 热插拔轴体','APPROVED','ON_SALE',399,399,'/covers/geek-spu-10005.jpg',2100,4.91,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10005);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10006,2000,3000,'SELF_OPERATED',430,500,'GEEK SELECT 8 合 1 扩展坞','自有品牌 · HDMI 4K / PD 100W','APPROVED','ON_SALE',329,329,'/covers/geek-spu-10006.jpg',1800,4.89,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10006);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10007,2003,3003,'THIRD_PARTY',210,502,'Orion Fold Z','内折大屏 · 官方旗舰店直供','APPROVED','ON_SALE',9999,9999,'/covers/geek-spu-10007.jpg',540,4.87,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10007);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10008,2001,3001,'THIRD_PARTY',220,501,'Nova Phone Ultra','一英寸主摄 · 官方旗舰','APPROVED','ON_SALE',5999,5999,'/covers/geek-spu-10008.jpg',1500,4.93,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10008);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10009,2002,3002,'THIRD_PARTY',420,504,'Aurora 竞技键鼠套装','第三方优选 · 低延迟双模','APPROVED','ON_SALE',459,459,'/covers/geek-spu-10009.jpg',980,4.80,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10009);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10010,2000,3000,'SELF_OPERATED',220,501,'Nova Phone Air','GEEK 自营精选 · 轻旗舰','APPROVED','ON_SALE',3999,3999,'/covers/geek-spu-10010.jpg',1120,4.86,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10010);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10011,2001,3001,'THIRD_PARTY',510,501,'NovaBuds Pro','空间音频 · 官方旗舰店','APPROVED','ON_SALE',899,899,'/covers/geek-spu-10011.jpg',2400,4.88,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10011);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10012,2000,3000,'SELF_OPERATED',600,505,'Nexus Band 7','GEEK 自营 · 运动健康手表','APPROVED','ON_SALE',1299,1299,'/covers/geek-spu-10012.jpg',760,4.84,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10012);
INSERT INTO product_spu(id,merchant_id,shop_id,source_type,category_id,brand_id,title,subtitle,audit_status,sale_status,min_price,max_price,cover_url,sales_count,rating,created_at,updated_at)
SELECT 10013,2000,3000,'SELF_OPERATED',510,500,'GEEK SELECT Buds Lite','自有品牌 · 轻量降噪通勤耳机','APPROVED','ON_SALE',499,499,'/covers/geek-spu-10013.jpg',1680,4.82,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM product_spu WHERE id=10013);

UPDATE product_spu SET cover_url='/covers/geek-spu-10003.jpg' WHERE id=10003;
UPDATE product_spu SET merchant_id=2000,shop_id=3000,source_type='SELF_OPERATED',category_id=410,brand_id=500,title='GEEK SELECT 100W 氮化镓充电器',subtitle='自有品牌 · 三口快充旅行装',audit_status='APPROVED',sale_status='ON_SALE',min_price=199,max_price=199,cover_url='/covers/geek-spu-10004.jpg' WHERE id=10004;
UPDATE product_spu SET cover_url='/covers/geek-spu-10005.jpg' WHERE id=10005;
UPDATE product_spu SET cover_url='/covers/geek-spu-10006.jpg' WHERE id=10006;
UPDATE product_spu SET cover_url='/covers/geek-spu-10007.jpg' WHERE id=10007;
UPDATE product_spu SET cover_url='/covers/geek-spu-10008.jpg' WHERE id=10008;
UPDATE product_spu SET cover_url='/covers/geek-spu-10009.jpg' WHERE id=10009;
UPDATE product_spu SET cover_url='/covers/geek-spu-10010.jpg' WHERE id=10010;
UPDATE product_spu SET cover_url='/covers/geek-spu-10011.jpg' WHERE id=10011;
UPDATE product_spu SET cover_url='/covers/geek-spu-10012.jpg' WHERE id=10012;
UPDATE product_spu SET cover_url='/covers/geek-spu-10013.jpg' WHERE id=10013;

INSERT INTO product_spu_detail VALUES
(10001,'<h2>轻盈，但不妥协性能</h2><p>Nova 官方旗舰店直供，适合开发、设计与移动办公。</p>','主机、100W 氮化镓电源、说明书','两年整机保修'),
(10002,'<h2>让每个像素准确表达</h2><p>MiniLED 精细控光，覆盖专业广色域。</p>','显示器、支架、DP 线、校色报告','三年上门服务'),
(10003,'<h2>平台自营精选创作本</h2><p>由 GEEK 自营采购 Orion 系列，正品保障。</p>','主机、电源、说明书','两年整机保修'),
(10004,'<h2>GEEK SELECT 自有品牌</h2><p>供应链 OEM 配件，旅行三口快充。</p>','充电器、收纳袋','一年质保'),
(10005,'<h2>敲击手感经过调校</h2><p>热插拔轴体，适合长时间编码。</p>','键盘、拔键器','一年质保'),
(10006,'<h2>一条线连接桌面</h2><p>扩展坞覆盖投屏、网口与 PD 供电。</p>','扩展坞、收纳袋','一年质保'),
(10007,'<h2>折叠大屏官方直供</h2><p>Orion 官方旗舰店发货。</p>','手机、保护壳、充电器','一年质保'),
(10008,'<h2>一英寸主摄影像</h2><p>Nova 官方旗舰影像系列。</p>','手机、充电器、保护壳','一年质保'),
(10009,'<h2>第三方优选外设</h2><p>未来数码专营店授权经销。</p>','键盘、鼠标、收纳包','一年质保'),
(10010,'<h2>自营精选轻旗舰</h2><p>GEEK 自营多品牌选品。</p>','手机、充电器','一年质保'),
(10011,'<h2>空间音频耳机</h2><p>Nova 官方旗舰店。</p>','耳机、充电盒','一年质保'),
(10012,'<h2>运动健康穿戴</h2><p>GEEK 自营精选。</p>','手表、充电线','一年质保'),
(10013,'<h2>轻量降噪通勤耳机</h2><p>GEEK SELECT 自有品牌，主打长续航与舒适佩戴。</p>','耳机、充电盒、耳塞套','一年质保')
ON DUPLICATE KEY UPDATE description_html=VALUES(description_html),package_list=VALUES(package_list),service_desc=VALUES(service_desc);

INSERT INTO product_sku(id,spu_id,sku_code,barcode,sale_price,market_price,cost_price,weight,volume,status,created_at,updated_at) VALUES
(11001,10001,'NV-A14-16-1T-B','6900000001101',6999,7499,5600,1190,1800,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(11002,10001,'NV-A14-32-1T-S','6900000001102',7999,8499,6400,1190,1800,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(12001,10002,'PC-C32-4K','6900000001201',4299,4799,3200,7200,24000,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(13001,10003,'OR-B16-32-1T','6900000001301',8999,9699,7200,1650,2200,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(14001,10004,'GS-CHG-100W','6900000001401',199,249,90,180,200,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(15001,10005,'GS-KB-TKL','6900000001501',399,459,180,780,1200,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(16001,10006,'GS-DOCK-8IN1','6900000001601',329,379,150,220,350,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(17001,10007,'OR-FOLD-Z','6900000001701',9999,10999,7800,255,400,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(18001,10008,'NV-PHONE-U','6900000001801',5999,6499,4500,210,350,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(19001,10009,'AU-KM-SET','6900000001901',459,519,210,900,1500,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(20001,10010,'NV-PHONE-AIR','6900000002001',3999,4299,3000,180,300,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(21001,10011,'NV-BUDS-P','6900000002101',899,999,420,55,80,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(22001,10012,'NX-BAND-7','6900000002201',1299,1499,620,45,60,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),
(23001,10013,'GS-BUDS-LITE','6900000002301',499,559,210,48,70,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE sale_price=VALUES(sale_price),market_price=VALUES(market_price),status=VALUES(status),spu_id=VALUES(spu_id),sku_code=VALUES(sku_code);

INSERT INTO product_sku_sale_attr VALUES
(13001,11001,702,801,'16GB'),(13002,11001,703,803,'1TB'),(13003,11001,704,804,'曜石黑'),
(13004,11002,702,802,'32GB'),(13005,11002,703,803,'1TB'),(13006,11002,704,805,'冰川银')
ON DUPLICATE KEY UPDATE value_text=VALUES(value_text);
INSERT INTO product_spu_attr_value(id,spu_id,attr_id,value_text,option_id) VALUES
(14001,10001,701,'Ultra 7 258V',NULL),(14002,10002,705,'3840×2160',806),(14003,10004,706,'100W',807)
ON DUPLICATE KEY UPDATE value_text=VALUES(value_text);

INSERT INTO merchant_application(id,application_no,applicant_user_id,merchant_id,company_name,credit_code,contact_name,contact_mobile,categories_json,status,created_at,updated_at)
SELECT 15001,'MA202607160001',4,NULL,'航迹智能硬件','91310000TRAIL001','林舟','13800000001','["智能穿戴","数码配件"]','SUBMITTED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM merchant_application WHERE id=15001);

INSERT INTO merchant_category_auth(id,merchant_id,category_id,status,valid_until,created_at) VALUES
(96001,2000,100,'ENABLED',NULL,CURRENT_TIMESTAMP),(96002,2000,110,'ENABLED',NULL,CURRENT_TIMESTAMP),
(96003,2000,200,'ENABLED',NULL,CURRENT_TIMESTAMP),(96004,2000,220,'ENABLED',NULL,CURRENT_TIMESTAMP),
(96005,2000,300,'ENABLED',NULL,CURRENT_TIMESTAMP),(96006,2000,400,'ENABLED',NULL,CURRENT_TIMESTAMP),
(96007,2000,410,'ENABLED',NULL,CURRENT_TIMESTAMP),(96008,2000,420,'ENABLED',NULL,CURRENT_TIMESTAMP),
(96009,2000,430,'ENABLED',NULL,CURRENT_TIMESTAMP),(96010,2000,600,'ENABLED',NULL,CURRENT_TIMESTAMP),
(96020,2000,510,'ENABLED',NULL,CURRENT_TIMESTAMP),
(96011,2001,100,'ENABLED',NULL,CURRENT_TIMESTAMP),(96012,2001,110,'ENABLED',NULL,CURRENT_TIMESTAMP),
(96013,2001,200,'ENABLED',NULL,CURRENT_TIMESTAMP),(96014,2001,220,'ENABLED',NULL,CURRENT_TIMESTAMP),
(96015,2001,510,'ENABLED',NULL,CURRENT_TIMESTAMP),
(96016,2002,100,'ENABLED',NULL,CURRENT_TIMESTAMP),(96017,2002,110,'ENABLED',NULL,CURRENT_TIMESTAMP),
(96018,2002,300,'ENABLED',NULL,CURRENT_TIMESTAMP),(96019,2002,420,'ENABLED',NULL,CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE status=VALUES(status),valid_until=VALUES(valid_until);

INSERT INTO merchant_brand_auth(id,merchant_id,brand_id,file_url,status,valid_until,created_at) VALUES
(96101,2000,500,'https://cdn.example/auth/geek-select.pdf','ENABLED',NULL,CURRENT_TIMESTAMP),
(96102,2000,501,'https://cdn.example/auth/nova-self.pdf','ENABLED',NULL,CURRENT_TIMESTAMP),
(96103,2000,502,'https://cdn.example/auth/orion-self.pdf','ENABLED',NULL,CURRENT_TIMESTAMP),
(96104,2000,503,'https://cdn.example/auth/pixelcraft-self.pdf','ENABLED',NULL,CURRENT_TIMESTAMP),
(96105,2000,505,'https://cdn.example/auth/nexus-self.pdf','ENABLED',NULL,CURRENT_TIMESTAMP),
(96106,2001,501,'https://cdn.example/auth/nova-flagship.pdf','ENABLED',NULL,CURRENT_TIMESTAMP),
(96107,2002,502,'https://cdn.example/auth/orion-reseller.pdf','ENABLED',NULL,CURRENT_TIMESTAMP),
(96108,2002,503,'https://cdn.example/auth/pixelcraft-reseller.pdf','ENABLED',NULL,CURRENT_TIMESTAMP),
(96109,2002,504,'https://cdn.example/auth/audiox.pdf','ENABLED',NULL,CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE status=VALUES(status),file_url=VALUES(file_url);

UPDATE shop SET cert_status='APPROVED',cert_brand_id=501 WHERE id=3001;
UPDATE shop SET cert_status='APPROVED',cert_brand_id=502 WHERE id=3003;
UPDATE shop SET cert_status='NONE',cert_brand_id=NULL WHERE id IN (3000,3002);

INSERT INTO shop_flagship_cert(id,shop_id,merchant_id,brand_id,evidence_url,status,auditor_id,created_at,updated_at)
SELECT 96201,3001,2001,501,'https://cdn.example/cert/nova.pdf','APPROVED',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM shop_flagship_cert WHERE id=96201);
INSERT INTO shop_flagship_cert(id,shop_id,merchant_id,brand_id,evidence_url,status,auditor_id,created_at,updated_at)
SELECT 96202,3002,2002,504,'https://cdn.example/cert/audiox-pending.pdf','PENDING',NULL,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM shop_flagship_cert WHERE id=96202);

