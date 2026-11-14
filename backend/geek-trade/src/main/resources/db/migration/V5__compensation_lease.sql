ALTER TABLE trade_compensation_task
    ADD COLUMN lease_token VARCHAR(36) NULL,
    ADD COLUMN lease_until TIMESTAMP(3) NULL,
    ADD INDEX idx_compensation_lease (status, lease_until);
