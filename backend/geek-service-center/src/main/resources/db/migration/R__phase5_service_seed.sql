INSERT INTO service_skill_group(id,group_code,group_name,status) VALUES
 (91001,'GENERAL','综合客服','ENABLED'),
 (91002,'AFTERSALE','售后专员','ENABLED')
ON DUPLICATE KEY UPDATE group_name=VALUES(group_name),status=VALUES(status);

INSERT INTO service_agent(id,user_id,display_name,skill_group_id,status,max_sessions) VALUES
 (91101,1,'平台客服小极',91001,'ONLINE',8)
ON DUPLICATE KEY UPDATE display_name=VALUES(display_name),status=VALUES(status);

INSERT INTO notification_template(id,scene,channel,template_code,content,status) VALUES
 (91201,'ORDER_PAID','IN_APP','ORDER_PAID_IN_APP','您的订单 {orderNo} 已支付成功','ENABLED'),
 (91202,'ORDER_PAID','SMS','ORDER_PAID_SMS','【极客商城】订单{orderNo}支付成功','ENABLED'),
 (91203,'AFTERSALE_APPROVED','IN_APP','AS_APPROVED','售后单 {aftersaleNo} 已通过','ENABLED')
ON DUPLICATE KEY UPDATE content=VALUES(content),status=VALUES(status);

-- 演示 SLA 超时工单（供大屏从 service_ticket 实时聚合）
INSERT INTO service_ticket(id,ticket_no,user_id,merchant_id,category,priority,status,assignee_id,title,content,sla_due_at,created_at,updated_at,resolved_at) VALUES
 (91301,'TK-DEMO-SLA-001',4,2001,'AFTERSALE','HIGH','OPEN',91101,'演示：售后超时未响应','用于运营大屏 SLA 告警聚合',DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 6 HOUR),DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY),CURRENT_TIMESTAMP(3),NULL),
 (91302,'TK-DEMO-SLA-002',4,NULL,'GENERAL','URGENT','PROCESSING',91101,'演示：紧急咨询超时','用于运营大屏 SLA 告警聚合',DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 3 HOUR),DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 8 HOUR),CURRENT_TIMESTAMP(3),NULL),
 (91303,'TK-DEMO-OPEN-001',4,NULL,'GENERAL','NORMAL','OPEN',NULL,'演示：未超时工单','对照样本，不应计入 SLA 违约',DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 12 HOUR),CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),NULL)
ON DUPLICATE KEY UPDATE status=VALUES(status),sla_due_at=VALUES(sla_due_at),title=VALUES(title),content=VALUES(content),updated_at=CURRENT_TIMESTAMP(3);

INSERT INTO notification_task(id,task_no,scene,channel,receiver_id,payload_json,status,created_at,updated_at) VALUES
 (91401,'NT-DEMO-001','ORDER_PAID','IN_APP',4,'{"orderNo":"DEMO-ORDER-1"}','SENT',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
 (91402,'NT-DEMO-002','AFTERSALE_APPROVED','IN_APP',4,'{"aftersaleNo":"AS-DEMO-1"}','SENT',DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 2 HOUR),CURRENT_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE status=VALUES(status),payload_json=VALUES(payload_json),updated_at=CURRENT_TIMESTAMP(3);
