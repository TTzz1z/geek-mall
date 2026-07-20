INSERT INTO settlement_commission_rule(id,merchant_id,category_id,rate,start_at,end_at,status) VALUES
 (92001,NULL,NULL,0.0500,DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 30 DAY),NULL,'ENABLED')
ON DUPLICATE KEY UPDATE rate=VALUES(rate),status=VALUES(status);

INSERT INTO merchant_deposit(merchant_id,total_amount,frozen_amount,status,updated_at) VALUES
 (2001,10000.00,0,'ACTIVE',CURRENT_TIMESTAMP),
 (2002,8000.00,0,'ACTIVE',CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE status=VALUES(status),updated_at=CURRENT_TIMESTAMP;

-- Demo metrics for today only when missing. IDs are day-scoped to avoid PK clashes across dates.
INSERT INTO analytics_metric_daily(id,metric_date,metric_code,dimension,metric_value,updated_at)
SELECT (TO_DAYS(CURDATE())*100 + 1),CURDATE(),'GMV','ALL',2836420.00,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM analytics_metric_daily WHERE metric_date=CURDATE() AND metric_code='GMV' AND dimension='ALL');
INSERT INTO analytics_metric_daily(id,metric_date,metric_code,dimension,metric_value,updated_at)
SELECT (TO_DAYS(CURDATE())*100 + 2),CURDATE(),'ORDER_COUNT','ALL',8492,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM analytics_metric_daily WHERE metric_date=CURDATE() AND metric_code='ORDER_COUNT' AND dimension='ALL');
INSERT INTO analytics_metric_daily(id,metric_date,metric_code,dimension,metric_value,updated_at)
SELECT (TO_DAYS(CURDATE())*100 + 3),CURDATE(),'PAID_USERS','ALL',26104,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM analytics_metric_daily WHERE metric_date=CURDATE() AND metric_code='PAID_USERS' AND dimension='ALL');
INSERT INTO analytics_metric_daily(id,metric_date,metric_code,dimension,metric_value,updated_at)
SELECT (TO_DAYS(CURDATE())*100 + 4),CURDATE(),'AI_CONVERT_RATE','ALL',0.1240,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM analytics_metric_daily WHERE metric_date=CURDATE() AND metric_code='AI_CONVERT_RATE' AND dimension='ALL');
INSERT INTO analytics_metric_daily(id,metric_date,metric_code,dimension,metric_value,updated_at)
SELECT (TO_DAYS(CURDATE())*100 + 5),CURDATE(),'GMV','PHONE',980000,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM analytics_metric_daily WHERE metric_date=CURDATE() AND metric_code='GMV' AND dimension='PHONE');
INSERT INTO analytics_metric_daily(id,metric_date,metric_code,dimension,metric_value,updated_at)
SELECT (TO_DAYS(CURDATE())*100 + 6),CURDATE(),'GMV','PC',860000,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM analytics_metric_daily WHERE metric_date=CURDATE() AND metric_code='GMV' AND dimension='PC');
INSERT INTO analytics_metric_daily(id,metric_date,metric_code,dimension,metric_value,updated_at)
SELECT (TO_DAYS(CURDATE())*100 + 7),CURDATE(),'GMV','ACCESSORY',620000,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM analytics_metric_daily WHERE metric_date=CURDATE() AND metric_code='GMV' AND dimension='ACCESSORY');
INSERT INTO analytics_metric_daily(id,metric_date,metric_code,dimension,metric_value,updated_at)
SELECT (TO_DAYS(CURDATE())*100 + 8),CURDATE(),'GMV','WEARABLE',376420,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM analytics_metric_daily WHERE metric_date=CURDATE() AND metric_code='GMV' AND dimension='WEARABLE');

-- 商家经营总览演示流水（按 merchant 隔离）
INSERT INTO settlement_pending_item(id,merchant_id,order_no,order_item_id,income,commission,refund_amount,status,created_at,updated_at)
SELECT 93001,2001,'GM202607150001',9300101,6999.00,349.95,0.00,'PENDING',DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 2 DAY),CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM settlement_pending_item WHERE id=93001);
INSERT INTO settlement_pending_item(id,merchant_id,order_no,order_item_id,income,commission,refund_amount,status,created_at,updated_at)
SELECT 93002,2001,'GM202607180011',9300102,1299.00,64.95,0.00,'PENDING',DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY),CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM settlement_pending_item WHERE id=93002);
INSERT INTO settlement_pending_item(id,merchant_id,order_no,order_item_id,income,commission,refund_amount,status,created_at,updated_at)
SELECT 93003,2001,'GM202607190021',9300103,899.00,44.95,120.00,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM settlement_pending_item WHERE id=93003);
INSERT INTO settlement_pending_item(id,merchant_id,order_no,order_item_id,income,commission,refund_amount,status,created_at,updated_at)
SELECT 93004,2002,'GM202607150002',9300201,4299.00,214.95,0.00,'PENDING',DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 DAY),CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM settlement_pending_item WHERE id=93004);
INSERT INTO settlement_pending_item(id,merchant_id,order_no,order_item_id,income,commission,refund_amount,status,created_at,updated_at)
SELECT 93005,2002,'GM202607190022',9300202,1599.00,79.95,0.00,'SETTLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS(SELECT 1 FROM settlement_pending_item WHERE id=93005);
