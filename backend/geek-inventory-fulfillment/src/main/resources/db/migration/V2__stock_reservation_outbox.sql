CREATE TABLE inventory_stock_reservation (
 id BIGINT PRIMARY KEY, reservation_no VARCHAR(64) NOT NULL, order_id BIGINT NOT NULL, order_no VARCHAR(64) NOT NULL,
 merchant_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, sku_id BIGINT NOT NULL, quantity INT NOT NULL,
 status VARCHAR(32) NOT NULL, expires_at TIMESTAMP(3) NOT NULL, created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 UNIQUE KEY uk_reservation_scope(order_id,warehouse_id,sku_id), KEY idx_reservation_order(order_no,status)
);
CREATE TABLE event_outbox (
 id BIGINT PRIMARY KEY,event_id VARCHAR(64) NOT NULL,aggregate_type VARCHAR(64) NOT NULL,aggregate_id VARCHAR(64) NOT NULL,
 event_type VARCHAR(128) NOT NULL,payload_json JSON NOT NULL,status VARCHAR(32) NOT NULL,retry_count INT NOT NULL DEFAULT 0,
 next_retry_at TIMESTAMP(3) NULL,created_at TIMESTAMP(3) NOT NULL,published_at TIMESTAMP(3) NULL,UNIQUE KEY uk_inventory_event_id(event_id)
);
CREATE TABLE event_consume_log (
 consumer_group VARCHAR(96) NOT NULL,event_id VARCHAR(64) NOT NULL,processed_at TIMESTAMP(3) NOT NULL,result VARCHAR(32) NOT NULL,
 PRIMARY KEY(consumer_group,event_id)
);
CREATE TABLE inventory_reconcile_log (
 id BIGINT PRIMARY KEY,reconcile_type VARCHAR(64) NOT NULL,business_no VARCHAR(64) NULL,difference_count INT NOT NULL,
 status VARCHAR(32) NOT NULL,detail VARCHAR(500) NULL,created_at TIMESTAMP(3) NOT NULL
);
