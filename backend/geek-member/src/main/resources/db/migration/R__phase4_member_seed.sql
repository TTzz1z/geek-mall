-- Member demo profiles: insert-only for balances (do not reset points on R__ re-run)
INSERT INTO member_profile(user_id,level_code,growth_value,point_balance,frozen_points,updated_at)
SELECT 4,'GOLD',2500,5000,0,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM member_profile WHERE user_id=4);
INSERT INTO member_profile(user_id,level_code,growth_value,point_balance,frozen_points,updated_at)
SELECT 7,'NORMAL',100,1000,0,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM member_profile WHERE user_id=7);
-- Legacy orphan demo ids kept for historical seeds only; do not refresh balances
INSERT INTO member_profile(user_id,level_code,growth_value,point_balance,frozen_points,updated_at)
SELECT 1001,'GOLD',2500,5000,0,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM member_profile WHERE user_id=1001);
INSERT INTO member_profile(user_id,level_code,growth_value,point_balance,frozen_points,updated_at)
SELECT 1002,'NORMAL',100,200,0,CURRENT_TIMESTAMP FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM member_profile WHERE user_id=1002);
