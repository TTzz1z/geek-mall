ALTER TABLE marketing_user_coupon
  ADD COLUMN freeze_order_no VARCHAR(64) NULL,
  ADD COLUMN frozen_amount DECIMAL(18,2) NOT NULL DEFAULT 0 AFTER used_order_no;

CREATE TABLE IF NOT EXISTS marketing_coupon_reservation (
  id BIGINT PRIMARY KEY,
  order_no VARCHAR(64) NOT NULL,
  user_coupon_id BIGINT NOT NULL,
  template_id BIGINT NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_coupon_res_order(order_no)
);

CREATE TABLE IF NOT EXISTS marketing_event_outbox (
  id BIGINT PRIMARY KEY,
  event_id VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  payload_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  retry_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP(3) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  published_at TIMESTAMP(3) NULL,
  UNIQUE KEY uk_mk_event_id(event_id),
  KEY idx_mk_outbox(status,next_retry_at)
);

CREATE TABLE IF NOT EXISTS marketing_event_consume_log (
  id BIGINT PRIMARY KEY,
  consumer_group VARCHAR(64) NOT NULL,
  event_id VARCHAR(64) NOT NULL,
  processed_at TIMESTAMP(3) NOT NULL,
  result VARCHAR(32) NOT NULL,
  detail VARCHAR(500) NULL,
  UNIQUE KEY uk_mk_consume(consumer_group,event_id)
);

ALTER TABLE marketing_seckill_order
  ADD COLUMN request_id VARCHAR(64) NULL AFTER quantity,
  ADD COLUMN trade_order_no VARCHAR(64) NULL AFTER status,
  ADD COLUMN expires_at TIMESTAMP(3) NULL AFTER trade_order_no,
  ADD COLUMN updated_at TIMESTAMP(3) NULL AFTER created_at;

CREATE UNIQUE INDEX uk_seckill_request ON marketing_seckill_order(request_id);

ALTER TABLE marketing_seckill_item
  ADD COLUMN warm_status VARCHAR(32) NOT NULL DEFAULT 'COLD' AFTER status;
