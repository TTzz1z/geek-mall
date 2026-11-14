ALTER TABLE aftersale_order
    ADD COLUMN refund_retry_at TIMESTAMP(3) NULL,
    ADD COLUMN refund_retry_count INT NOT NULL DEFAULT 0,
    ADD INDEX idx_aftersale_refund_due (status, refund_retry_at);
