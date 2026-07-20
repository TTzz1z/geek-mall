CREATE TABLE trade_cart_item (
 id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,shop_id BIGINT NOT NULL,merchant_id BIGINT NOT NULL,spu_id BIGINT NOT NULL,sku_id BIGINT NOT NULL,
 quantity INT NOT NULL,selected BOOLEAN NOT NULL DEFAULT TRUE,added_price DECIMAL(18,2) NOT NULL,title_snapshot VARCHAR(255) NOT NULL,
 sku_snapshot JSON NOT NULL,cover_url VARCHAR(500) NULL,created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,
 UNIQUE KEY uk_cart_user_sku(user_id,sku_id),KEY idx_cart_user(user_id,updated_at)
);
CREATE TABLE trade_checkout_snapshot (
 id BIGINT PRIMARY KEY,checkout_token VARCHAR(96) NOT NULL,user_id BIGINT NOT NULL,request_hash VARCHAR(64) NOT NULL,snapshot_json JSON NOT NULL,
 status VARCHAR(32) NOT NULL,result_order_no VARCHAR(64) NULL,expires_at TIMESTAMP(3) NOT NULL,created_at TIMESTAMP(3) NOT NULL,consumed_at TIMESTAMP(3) NULL,
 UNIQUE KEY uk_checkout_token(checkout_token),KEY idx_checkout_user(user_id,status,expires_at)
);
CREATE TABLE trade_order (
 id BIGINT PRIMARY KEY,order_no VARCHAR(64) NOT NULL,user_id BIGINT NOT NULL,total_amount DECIMAL(18,2) NOT NULL,discount_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
 freight_amount DECIMAL(18,2) NOT NULL DEFAULT 0,payable_amount DECIMAL(18,2) NOT NULL,paid_amount DECIMAL(18,2) NOT NULL DEFAULT 0,status VARCHAR(32) NOT NULL,
 checkout_token VARCHAR(96) NOT NULL,paid_payment_id BIGINT NULL,inventory_status VARCHAR(32) NOT NULL,version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,paid_at TIMESTAMP(3) NULL,closed_at TIMESTAMP(3) NULL,
 UNIQUE KEY uk_order_no(order_no),UNIQUE KEY uk_order_checkout(checkout_token),KEY idx_order_user_status(user_id,status,created_at)
);
CREATE TABLE trade_sub_order (
 id BIGINT PRIMARY KEY,sub_order_no VARCHAR(64) NOT NULL,order_id BIGINT NOT NULL,merchant_id BIGINT NOT NULL,shop_id BIGINT NOT NULL,shop_name VARCHAR(128) NOT NULL,
 total_amount DECIMAL(18,2) NOT NULL,discount_amount DECIMAL(18,2) NOT NULL,payable_amount DECIMAL(18,2) NOT NULL,status VARCHAR(32) NOT NULL,
 remark VARCHAR(500) NULL,created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,UNIQUE KEY uk_sub_order_no(sub_order_no),KEY idx_sub_order_order(order_id)
);
CREATE TABLE trade_order_item (
 id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,sub_order_id BIGINT NOT NULL,spu_id BIGINT NOT NULL,sku_id BIGINT NOT NULL,title VARCHAR(255) NOT NULL,
 cover_url VARCHAR(500) NULL,sku_snapshot JSON NOT NULL,quantity INT NOT NULL,unit_price DECIMAL(18,2) NOT NULL,discount_amount DECIMAL(18,2) NOT NULL,
 payable_amount DECIMAL(18,2) NOT NULL,aftersale_qty INT NOT NULL DEFAULT 0,created_at TIMESTAMP(3) NOT NULL,KEY idx_order_item_order(order_id)
);
CREATE TABLE trade_order_address (
 order_id BIGINT PRIMARY KEY,address_id BIGINT NULL,receiver VARCHAR(96) NOT NULL,mobile VARCHAR(32) NOT NULL,region_code VARCHAR(32) NOT NULL,detail VARCHAR(255) NOT NULL
);
CREATE TABLE trade_order_discount (
 id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,order_item_id BIGINT NULL,discount_type VARCHAR(64) NOT NULL,source_id VARCHAR(64) NULL,amount DECIMAL(18,2) NOT NULL,snapshot_json JSON NOT NULL
);
CREATE TABLE trade_discount_reservation (
 id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,source_type VARCHAR(64) NOT NULL,source_id VARCHAR(64) NOT NULL,amount DECIMAL(18,2) NOT NULL,status VARCHAR(32) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,UNIQUE KEY uk_discount_reservation(order_id,source_type,source_id)
);
CREATE TABLE trade_fulfillment_plan (
 id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,sub_order_id BIGINT NOT NULL,warehouse_id BIGINT NOT NULL,sku_id BIGINT NOT NULL,quantity INT NOT NULL,status VARCHAR(32) NOT NULL,
 UNIQUE KEY uk_fulfillment_plan(order_id,warehouse_id,sku_id)
);
CREATE TABLE trade_order_log (
 id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,from_status VARCHAR(32) NULL,to_status VARCHAR(32) NOT NULL,operator_type VARCHAR(32) NOT NULL,operator_id BIGINT NOT NULL,
 detail VARCHAR(500) NULL,created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE trade_order_timeout (
 id BIGINT PRIMARY KEY,order_id BIGINT NOT NULL,timeout_type VARCHAR(32) NOT NULL,execute_at TIMESTAMP(3) NOT NULL,status VARCHAR(32) NOT NULL,
 retry_count INT NOT NULL DEFAULT 0,updated_at TIMESTAMP(3) NOT NULL,UNIQUE KEY uk_order_timeout(order_id,timeout_type),KEY idx_timeout_due(status,execute_at)
);
CREATE TABLE trade_idempotency (
 idempotency_key VARCHAR(96) NOT NULL,user_id BIGINT NOT NULL,business_type VARCHAR(32) NOT NULL,request_hash VARCHAR(64) NOT NULL,result_ref VARCHAR(64) NOT NULL,
 expires_at TIMESTAMP(3) NOT NULL,created_at TIMESTAMP(3) NOT NULL,PRIMARY KEY(idempotency_key,user_id,business_type)
);
CREATE TABLE trade_payment (
 id BIGINT PRIMARY KEY,payment_no VARCHAR(64) NOT NULL,order_id BIGINT NOT NULL,user_id BIGINT NOT NULL,channel VARCHAR(32) NOT NULL,merchant_no VARCHAR(64) NOT NULL,
 amount DECIMAL(18,2) NOT NULL,status VARCHAR(32) NOT NULL,channel_trade_no VARCHAR(96) NULL,created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,paid_at TIMESTAMP(3) NULL,
 UNIQUE KEY uk_payment_no(payment_no),KEY idx_payment_order(order_id,status)
);
CREATE TABLE trade_payment_callback (
 id BIGINT PRIMARY KEY,channel VARCHAR(32) NOT NULL,callback_id VARCHAR(96) NOT NULL,payload_hash VARCHAR(64) NOT NULL,payment_no VARCHAR(64) NOT NULL,
 process_status VARCHAR(32) NOT NULL,detail VARCHAR(500) NULL,created_at TIMESTAMP(3) NOT NULL,processed_at TIMESTAMP(3) NULL,
 UNIQUE KEY uk_payment_callback(channel,callback_id)
);
CREATE TABLE event_outbox (
 id BIGINT PRIMARY KEY,event_id VARCHAR(64) NOT NULL,aggregate_type VARCHAR(64) NOT NULL,aggregate_id VARCHAR(64) NOT NULL,event_type VARCHAR(128) NOT NULL,
 payload_json JSON NOT NULL,status VARCHAR(32) NOT NULL,retry_count INT NOT NULL DEFAULT 0,next_retry_at TIMESTAMP(3) NULL,created_at TIMESTAMP(3) NOT NULL,published_at TIMESTAMP(3) NULL,
 UNIQUE KEY uk_trade_event_id(event_id),KEY idx_trade_outbox(status,next_retry_at)
);
CREATE TABLE trade_compensation_task (
 id BIGINT PRIMARY KEY,task_type VARCHAR(64) NOT NULL,business_no VARCHAR(64) NOT NULL,payload_json JSON NOT NULL,status VARCHAR(32) NOT NULL,retry_count INT NOT NULL DEFAULT 0,
 next_retry_at TIMESTAMP(3) NOT NULL,last_error VARCHAR(500) NULL,created_at TIMESTAMP(3) NOT NULL,updated_at TIMESTAMP(3) NOT NULL,
 UNIQUE KEY uk_compensation_task(task_type,business_no),KEY idx_compensation_due(status,next_retry_at)
);
CREATE TABLE trade_reconcile_log (
 id BIGINT PRIMARY KEY,reconcile_type VARCHAR(64) NOT NULL,business_no VARCHAR(64) NULL,difference_count INT NOT NULL,status VARCHAR(32) NOT NULL,detail VARCHAR(500) NULL,created_at TIMESTAMP(3) NOT NULL
);
