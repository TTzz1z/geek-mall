CREATE TABLE inventory_warehouse (
 id BIGINT PRIMARY KEY, warehouse_code VARCHAR(32) NOT NULL UNIQUE, warehouse_name VARCHAR(128) NOT NULL,
 region_code VARCHAR(32) NOT NULL, address VARCHAR(255) NOT NULL, priority INT NOT NULL DEFAULT 0,
 status VARCHAR(32) NOT NULL, created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE inventory_sku_stock (
 id BIGINT PRIMARY KEY, merchant_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
 physical_qty INT NOT NULL DEFAULT 0, locked_qty INT NOT NULL DEFAULT 0, in_transit_qty INT NOT NULL DEFAULT 0,
 defective_qty INT NOT NULL DEFAULT 0, warning_qty INT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 UNIQUE KEY uk_stock_scope(merchant_id,warehouse_id,sku_id), KEY idx_stock_warning(merchant_id,physical_qty,locked_qty,warning_qty)
);
CREATE TABLE inventory_stock_log (
 id BIGINT PRIMARY KEY, merchant_id BIGINT NOT NULL, warehouse_id BIGINT NOT NULL, sku_id BIGINT NOT NULL,
 business_type VARCHAR(32) NOT NULL, business_no VARCHAR(64) NOT NULL, change_physical INT NOT NULL,
 change_locked INT NOT NULL DEFAULT 0, before_physical INT NOT NULL, after_physical INT NOT NULL,
 operator_id BIGINT NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 UNIQUE KEY uk_stock_operation(merchant_id,business_no,sku_id,business_type), KEY idx_stock_log_scope(merchant_id,warehouse_id,sku_id,created_at)
);
CREATE TABLE inventory_idempotency (
 idempotency_key VARCHAR(96) NOT NULL, merchant_id BIGINT NOT NULL, business_type VARCHAR(32) NOT NULL,
 result_ref VARCHAR(64) NOT NULL, created_at TIMESTAMP(3) NOT NULL, PRIMARY KEY(idempotency_key,merchant_id,business_type)
);
