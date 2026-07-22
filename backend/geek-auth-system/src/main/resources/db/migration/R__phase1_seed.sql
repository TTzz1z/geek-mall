INSERT INTO sys_user(id, username, password_hash, user_type, status, created_at, updated_at)
SELECT 1, 'admin', '$pbkdf2$210000$Z2Vlay1tYWxsLXNlZWQtMQ==$vk6kHDUwHYZ4cpRP0/CSCPd/bM67fWO2WWlUxW4CAz8=', 'ADMIN', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 1);
INSERT INTO sys_user(id, username, password_hash, user_type, status, created_at, updated_at)
SELECT 2, 'merchant_a', '$pbkdf2$210000$Z2Vlay1tYWxsLXNlZWQtMQ==$vk6kHDUwHYZ4cpRP0/CSCPd/bM67fWO2WWlUxW4CAz8=', 'MERCHANT', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 2);
INSERT INTO sys_user(id, username, password_hash, user_type, status, created_at, updated_at)
SELECT 3, 'merchant_b', '$pbkdf2$210000$Z2Vlay1tYWxsLXNlZWQtMQ==$vk6kHDUwHYZ4cpRP0/CSCPd/bM67fWO2WWlUxW4CAz8=', 'MERCHANT', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 3);
INSERT INTO sys_user(id, username, password_hash, user_type, status, created_at, updated_at)
SELECT 4, 'shopper', '$pbkdf2$210000$Z2Vlay1tYWxsLXNlZWQtMQ==$vk6kHDUwHYZ4cpRP0/CSCPd/bM67fWO2WWlUxW4CAz8=', 'USER', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 4);

INSERT INTO sys_role(id, role_code, role_name, data_scope, status, created_at, updated_at)
SELECT 10, 'SYSTEM_ADMIN', '系统管理员', 'PLATFORM', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 10);
INSERT INTO sys_role(id, role_code, role_name, data_scope, status, created_at, updated_at)
SELECT 20, 'MERCHANT', '商家管理员', 'MERCHANT', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 20);
INSERT INTO sys_role(id, role_code, role_name, data_scope, status, created_at, updated_at)
SELECT 15, 'OPS', '平台运营', 'PLATFORM', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 15);
INSERT INTO sys_role(id, role_code, role_name, data_scope, status, created_at, updated_at)
SELECT 16, 'PRODUCT_ADMIN', '商品管理员', 'PLATFORM', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 16);
INSERT INTO sys_role(id, role_code, role_name, data_scope, status, created_at, updated_at)
SELECT 30, 'USER', '商城用户', 'SELF', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_role WHERE id = 30);

INSERT INTO sys_user(id, username, password_hash, user_type, status, created_at, updated_at)
SELECT 5, 'operator', '$pbkdf2$210000$Z2Vlay1tYWxsLXNlZWQtMQ==$vk6kHDUwHYZ4cpRP0/CSCPd/bM67fWO2WWlUxW4CAz8=', 'ADMIN', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 5);
INSERT INTO sys_user(id, username, password_hash, user_type, status, created_at, updated_at)
SELECT 6, 'product_admin', '$pbkdf2$210000$Z2Vlay1tYWxsLXNlZWQtMQ==$vk6kHDUwHYZ4cpRP0/CSCPd/bM67fWO2WWlUxW4CAz8=', 'ADMIN', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 6);
-- E2E / 验收专用用户：脚本默认使用，避免污染演示账号 shopper 购物车
INSERT INTO sys_user(id, username, password_hash, user_type, status, created_at, updated_at)
SELECT 7, 'e2e_tester', '$pbkdf2$210000$Z2Vlay1tYWxsLXNlZWQtMQ==$vk6kHDUwHYZ4cpRP0/CSCPd/bM67fWO2WWlUxW4CAz8=', 'USER', 'ENABLED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE id = 7);

INSERT INTO sys_user_role(user_id, role_id) SELECT 1, 10 WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE user_id=1 AND role_id=10);
INSERT INTO sys_user_role(user_id, role_id) SELECT 2, 20 WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE user_id=2 AND role_id=20);
INSERT INTO sys_user_role(user_id, role_id) SELECT 3, 20 WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE user_id=3 AND role_id=20);
INSERT INTO sys_user_role(user_id, role_id) SELECT 4, 30 WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE user_id=4 AND role_id=30);
INSERT INTO sys_user_role(user_id, role_id) SELECT 5, 15 WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE user_id=5 AND role_id=15);
INSERT INTO sys_user_role(user_id, role_id) SELECT 6, 16 WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE user_id=6 AND role_id=16);
INSERT INTO sys_user_role(user_id, role_id) SELECT 7, 30 WHERE NOT EXISTS (SELECT 1 FROM sys_user_role WHERE user_id=7 AND role_id=30);

INSERT INTO sys_menu(id,parent_id,menu_name,route_path,icon,permission_code,menu_type,sort_order,status,created_at,updated_at)
SELECT 600,NULL,'首页装修','/page-decoration','Layout','cms:page:edit','MENU',15,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=600);
INSERT INTO sys_menu(id,parent_id,menu_name,route_path,icon,permission_code,menu_type,sort_order,status,created_at,updated_at)
SELECT 610,NULL,'分类品牌属性','/catalog-config','TreeStructure','catalog:manage','MENU',25,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=610);

INSERT INTO sys_role_menu(role_id,menu_id) SELECT 10,600 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=10 AND menu_id=600);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 10,610 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=10 AND menu_id=610);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 15,100 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=15 AND menu_id=100);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 15,200 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=15 AND menu_id=200);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 15,600 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=15 AND menu_id=600);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 16,100 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=16 AND menu_id=100);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 16,200 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=16 AND menu_id=200);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 16,610 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=16 AND menu_id=610);

INSERT INTO sys_menu(id,parent_id,menu_name,route_path,icon,permission_code,menu_type,sort_order,status,created_at,updated_at)
SELECT 100,NULL,'经营总览','/dashboard','Gauge','dashboard:view','MENU',10,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=100);
INSERT INTO sys_menu(id,parent_id,menu_name,route_path,icon,permission_code,menu_type,sort_order,status,created_at,updated_at)
SELECT 200,NULL,'商品中心','/products','Package','merchant:product:view','MENU',20,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=200);
INSERT INTO sys_menu(id,parent_id,menu_name,route_path,icon,permission_code,menu_type,sort_order,status,created_at,updated_at)
SELECT 300,NULL,'订单履约','/orders','Receipt','merchant:order:view','MENU',30,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=300);
INSERT INTO sys_menu(id,parent_id,menu_name,route_path,icon,permission_code,menu_type,sort_order,status,created_at,updated_at)
SELECT 400,NULL,'商家结算','/settlements','Wallet','merchant:settlement:view','MENU',40,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=400);
INSERT INTO sys_menu(id,parent_id,menu_name,route_path,icon,permission_code,menu_type,sort_order,status,created_at,updated_at)
SELECT 500,NULL,'系统用户','/system/users','Users','system:user:view','MENU',50,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=500);
INSERT INTO sys_menu(id,parent_id,menu_name,route_path,icon,permission_code,menu_type,sort_order,status,created_at,updated_at)
SELECT 900,NULL,'全部权限',NULL,'Shield','*','BUTTON',99,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=900);

INSERT INTO sys_role_menu(role_id,menu_id) SELECT 10,100 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=10 AND menu_id=100);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 10,200 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=10 AND menu_id=200);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 10,300 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=10 AND menu_id=300);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 10,400 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=10 AND menu_id=400);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 10,500 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=10 AND menu_id=500);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 10,900 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=10 AND menu_id=900);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 20,100 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=20 AND menu_id=100);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 20,200 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=20 AND menu_id=200);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 20,300 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=20 AND menu_id=300);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 20,400 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=20 AND menu_id=400);

INSERT INTO merchant_user(id,merchant_id,sys_user_id,shop_id,status,created_at,updated_at)
SELECT 1,2001,2,3001,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM merchant_user WHERE id=1);
INSERT INTO merchant_user(id,merchant_id,sys_user_id,shop_id,status,created_at,updated_at)
SELECT 2,2002,3,3002,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM merchant_user WHERE id=2);
-- product_admin 代运营 GEEK 自营店
INSERT INTO merchant_user(id,merchant_id,sys_user_id,shop_id,status,created_at,updated_at)
SELECT 3,2000,6,3000,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM merchant_user WHERE id=3);
-- 平台运营同样代运营 GEEK 自营店（侧栏「我的商品」）
INSERT INTO merchant_user(id,merchant_id,sys_user_id,shop_id,status,created_at,updated_at)
SELECT 4,2000,5,3000,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM merchant_user WHERE id=4);

INSERT INTO sys_menu(id,parent_id,menu_name,route_path,icon,permission_code,menu_type,sort_order,status,created_at,updated_at)
SELECT 620,NULL,'商家授权治理','/merchant-auth','ShieldCheck','governance:merchant:auth','MENU',22,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=620);
INSERT INTO sys_menu(id,parent_id,menu_name,route_path,icon,permission_code,menu_type,sort_order,status,created_at,updated_at)
SELECT 630,NULL,'旗舰认证','/flagship-certs','Badge','governance:flagship','MENU',23,'ENABLED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP WHERE NOT EXISTS(SELECT 1 FROM sys_menu WHERE id=630);

INSERT INTO sys_role_menu(role_id,menu_id) SELECT 10,620 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=10 AND menu_id=620);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 10,630 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=10 AND menu_id=630);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 16,620 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=16 AND menu_id=620);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 16,630 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=16 AND menu_id=630);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 15,630 WHERE NOT EXISTS(SELECT 1 FROM sys_role_menu WHERE role_id=15 AND menu_id=630);
