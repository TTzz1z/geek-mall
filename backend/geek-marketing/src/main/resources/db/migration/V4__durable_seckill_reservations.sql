ALTER TABLE marketing_seckill_item
  ADD COLUMN reserved_qty BIGINT NOT NULL DEFAULT 0;

ALTER TABLE marketing_seckill_order
  ADD COLUMN address_id BIGINT NULL AFTER request_id,
  ADD KEY idx_seckill_expiry(status,expires_at,id);

-- Run after every legacy marketing instance has stopped writing. Redis receipts
-- may have been lost, so only the committed order rows determine SQL holds.
UPDATE marketing_seckill_item i
JOIN (
  SELECT session_id,sku_id,SUM(quantity) AS held
  FROM marketing_seckill_order
  WHERE status IN ('QUEUED','CREATED','TIMEOUT') AND quantity>0
  GROUP BY session_id,sku_id
) o ON o.session_id=i.session_id AND o.sku_id=i.sku_id
SET i.reserved_qty=o.held;

UPDATE marketing_seckill_order
SET status='RELEASED',updated_at=CURRENT_TIMESTAMP
WHERE status='RELEASE_PENDING';

UPDATE marketing_seckill_order
SET expires_at=TIMESTAMPADD(SECOND,900,created_at)
WHERE status='QUEUED' AND expires_at IS NULL;

UPDATE marketing_seckill_order o
JOIN marketing_event_outbox e ON CAST(e.aggregate_id AS BINARY)=CAST(o.id AS BINARY)
SET o.address_id=JSON_VALUE(e.payload_json,'$.payload.addressId' RETURNING SIGNED)
WHERE e.aggregate_type='SECKILL_ORDER' AND e.event_type='marketing.seckill.order.v1'
  AND JSON_VALUE(e.payload_json,'$.payload.addressId' RETURNING SIGNED)>0;
