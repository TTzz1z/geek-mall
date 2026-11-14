CREATE TABLE trade_cart_mutation (
 user_id BIGINT NOT NULL,
 step_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 sku_id BIGINT NOT NULL,
 quantity INT NOT NULL,
 result_json JSON NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(user_id,step_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
