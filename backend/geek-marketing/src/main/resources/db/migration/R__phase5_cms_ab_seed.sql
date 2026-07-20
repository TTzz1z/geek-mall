-- Homepage CMS floors for brand Phase-2 (publishable blocks)
UPDATE cms_page_version SET status='ARCHIVED' WHERE page_code='HOME' AND status='PUBLISHED';

INSERT INTO cms_page_version(id,page_code,version_no,title,blocks_json,status,published_at,created_by,created_at)
SELECT 94010,'HOME',2,'首页多主体楼层 v2',
'[{"type":"HERO","title":"OrionBook Pro 16","subtitle":"GEEK 自营精选 · 多品牌正品","cta":"立即选购","spuId":10003},{"type":"PRODUCT_FLOOR","code":"geek_select","title":"GEEK SELECT 自有品牌","subtitle":"充电、键鼠、扩展坞等配件","spuIds":[10004,10005,10006]},{"type":"PRODUCT_FLOOR","code":"flagship","title":"品牌旗舰店","subtitle":"官方直营旗舰精选","spuIds":[10001,10007,10008,10011]},{"type":"PRODUCT_FLOOR","code":"phones","title":"热门手机","subtitle":"影像旗舰与折叠屏","spuIds":[10008,10007,10010]},{"type":"PRODUCT_FLOOR","code":"computers","title":"电脑办公","subtitle":"笔记本与专业显示","spuIds":[10001,10003,10002]},{"type":"PRODUCT_FLOOR","code":"third_party","title":"第三方优选","subtitle":"授权专营与优选外设","spuIds":[10002,10009]},{"type":"SECKILL","code":"seckill","title":"限时秒杀","subtitle":"场次与库存实时同步"}]',
'PUBLISHED',CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM cms_page_version WHERE id=94010);

UPDATE cms_page_version SET title='首页多主体楼层 v2',
blocks_json='[{"type":"HERO","title":"OrionBook Pro 16","subtitle":"GEEK 自营精选 · 多品牌正品","cta":"立即选购","spuId":10003},{"type":"PRODUCT_FLOOR","code":"geek_select","title":"GEEK SELECT 自有品牌","subtitle":"充电、键鼠、扩展坞等配件","spuIds":[10004,10005,10006]},{"type":"PRODUCT_FLOOR","code":"flagship","title":"品牌旗舰店","subtitle":"官方直营旗舰精选","spuIds":[10001,10007,10008,10011]},{"type":"PRODUCT_FLOOR","code":"phones","title":"热门手机","subtitle":"影像旗舰与折叠屏","spuIds":[10008,10007,10010]},{"type":"PRODUCT_FLOOR","code":"computers","title":"电脑办公","subtitle":"笔记本与专业显示","spuIds":[10001,10003,10002]},{"type":"PRODUCT_FLOOR","code":"third_party","title":"第三方优选","subtitle":"授权专营与优选外设","spuIds":[10002,10009]},{"type":"SECKILL","code":"seckill","title":"限时秒杀","subtitle":"场次与库存实时同步"}]',
status='PUBLISHED',published_at=CURRENT_TIMESTAMP
WHERE id=94010;

INSERT INTO cms_page_version(id,page_code,version_no,title,blocks_json,status,published_at,created_by,created_at)
SELECT 94001,'HOME',1,'首页 v1（可回滚）','[{"type":"BANNER","title":"极客本新品","image":"/assets/hero.png"},{"type":"CATEGORY","items":["手机","电脑","配件"]},{"type":"DEAL","title":"本周热门"}]','ARCHIVED',CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM cms_page_version WHERE id=94001);

UPDATE cms_page_version SET status='ARCHIVED' WHERE id=94001 AND page_code='HOME';

INSERT INTO cms_page_version(id,page_code,version_no,title,blocks_json,status,published_at,created_by,created_at)
SELECT 94002,'SHOP_HOME',1,'店铺首页模板','[{"type":"SHOP_BANNER","title":"店铺主推"},{"type":"PRODUCT_GRID","limit":8}]','PUBLISHED',CURRENT_TIMESTAMP,1,CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM cms_page_version WHERE id=94002);

INSERT INTO ab_experiment(id,experiment_code,name,status,traffic_percent,created_at) VALUES
 (94101,'HOME_HERO','首页主视觉实验','RUNNING',100,CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE name=VALUES(name),status=VALUES(status);

INSERT INTO ab_variant(id,experiment_id,variant_code,weight,config_json) VALUES
 (94201,94101,'A',50,'{"heroTitle":"把灵感带到更远的地方","cta":"立即选购"}'),
 (94202,94101,'B',50,'{"heroTitle":"用参数说话的选购体验","cta":"对比选购"}')
ON DUPLICATE KEY UPDATE weight=VALUES(weight),config_json=VALUES(config_json);
