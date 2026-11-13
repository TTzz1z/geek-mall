CREATE TABLE settlement_order_item_lock (
    order_item_id BIGINT PRIMARY KEY
);

CREATE TABLE settlement_refund_application (
    refund_no VARCHAR(64) PRIMARY KEY,
    order_item_id BIGINT NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    KEY idx_settlement_refund_item (order_item_id)
);
