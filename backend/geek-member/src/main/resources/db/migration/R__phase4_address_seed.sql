-- Addresses: insert-only (do not rewrite demo detail on R__ re-run)
INSERT INTO member_user_address(id, user_id, receiver, mobile, region_code, region_name, detail, is_default, created_at, updated_at)
SELECT 81004, 4, '极客用户', '13800000001', '310000', '上海市', '嘉定区极客路 88 号', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM member_user_address WHERE id=81004);
INSERT INTO member_user_address(id, user_id, receiver, mobile, region_code, region_name, detail, is_default, created_at, updated_at)
SELECT 81007, 7, '验收用户', '13900000007', '310000', '上海市', '验收收货地址 7 号', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM member_user_address WHERE id=81007);
INSERT INTO member_user_address(id, user_id, receiver, mobile, region_code, region_name, detail, is_default, created_at, updated_at)
SELECT 81001, 1001, '极客用户', '13800000001', '310000', '上海市', '嘉定区极客路 88 号', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM member_user_address WHERE id=81001);
INSERT INTO member_user_address(id, user_id, receiver, mobile, region_code, region_name, detail, is_default, created_at, updated_at)
SELECT 81002, 1002, '极客买家', '13800000002', '110000', '北京市', '朝阳区极客大道 1 号', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM member_user_address WHERE id=81002);
