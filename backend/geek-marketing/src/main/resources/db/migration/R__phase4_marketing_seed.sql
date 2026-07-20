INSERT INTO marketing_campaign(id,campaign_type,name,rule_json,status,created_at)
VALUES (73001,'FULL_REDUCTION','满10000减200平台满减','{"threshold":"10000","discount":"200"}','ENABLED',CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE name=VALUES(name),rule_json=VALUES(rule_json),status=VALUES(status);

INSERT INTO marketing_coupon_template(id,name,threshold_amount,discount_amount,total_qty,received_qty,start_at,end_at,status,created_at)
VALUES
 (71001,'满1000减100平台券',1000.00,100.00,1000,0,DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY),DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 30 DAY),'ENABLED',CURRENT_TIMESTAMP),
 (71002,'满500减50店铺券',500.00,50.00,500,0,DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY),DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 30 DAY),'ENABLED',CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE name=VALUES(name),threshold_amount=VALUES(threshold_amount),discount_amount=VALUES(discount_amount),status=VALUES(status);

-- 不重置 status：避免 R__ 重跑把已使用券改回 UNUSED
INSERT INTO marketing_user_coupon(id,template_id,user_id,status,received_at)
SELECT 71104,71001,4,'UNUSED',CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM marketing_user_coupon WHERE id=71104);
INSERT INTO marketing_user_coupon(id,template_id,user_id,status,received_at)
SELECT 71107,71001,7,'UNUSED',CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM marketing_user_coupon WHERE id=71107);
INSERT INTO marketing_user_coupon(id,template_id,user_id,status,received_at)
SELECT 71101,71001,1001,'UNUSED',CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM marketing_user_coupon WHERE id=71101);

INSERT INTO marketing_seckill_session(id,session_code,start_at,end_at,status)
VALUES (72001,'DEMO-SESSION',DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 10 MINUTE),DATE_ADD(CURRENT_TIMESTAMP,INTERVAL 2 HOUR),'ENABLED')
ON DUPLICATE KEY UPDATE start_at=VALUES(start_at),end_at=VALUES(end_at),status=VALUES(status);

INSERT INTO marketing_seckill_item(id,session_id,sku_id,price,stock_qty,limit_per_user,status,warm_status)
VALUES
 (72101,72001,11001,5999.00,50,1,'ENABLED','COLD'),
 (72102,72001,14001,149.00,80,2,'ENABLED','COLD'),
 (72103,72001,18001,4999.00,40,1,'ENABLED','COLD')
-- 不覆盖 stock_qty / warm_status：避免重跑抹掉秒杀库存与预热态
ON DUPLICATE KEY UPDATE price=VALUES(price),limit_per_user=VALUES(limit_per_user),status=VALUES(status);
