CREATE TABLE trade_seckill_progress (
    order_no VARCHAR(64) PRIMARY KEY,
    request_id VARCHAR(96) NOT NULL,
    payload_json JSON NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    UNIQUE KEY uk_seckill_progress_request(request_id)
);

-- Reconstruct the immutable request from committed order snapshots. Earlier versions
-- did not persist the marketing order id, so recovery confirms by request_id.
INSERT INTO trade_seckill_progress(order_no,request_id,payload_json,expires_at,phase,updated_at)
SELECT o.order_no,SUBSTRING(o.checkout_token,9),
    JSON_OBJECT('requestId',SUBSTRING(o.checkout_token,9),
        'sessionId',JSON_EXTRACT(d.snapshot_json,'$.sessionId'),
        'skuId',i.sku_id,'userId',o.user_id,'quantity',i.quantity),
    COALESCE(t.execute_at,TIMESTAMPADD(SECOND,900,o.created_at)),
    CASE WHEN o.status IN ('RESERVING','RESERVATION_RETRY') THEN 'PREPARED'
        WHEN o.status='PENDING_PAYMENT' THEN 'STOCK_RESERVED'
        WHEN o.status='CANCELLED' THEN 'CANCELLED' ELSE 'COMPLETED' END,
    CURRENT_TIMESTAMP
FROM trade_order o
JOIN trade_order_item i ON i.order_id=o.id
JOIN trade_order_discount d ON d.order_id=o.id AND d.discount_type='SECKILL'
    AND (d.order_item_id=i.id OR d.order_item_id IS NULL)
LEFT JOIN trade_order_timeout t ON t.order_id=o.id AND t.timeout_type='PAYMENT'
WHERE o.order_no LIKE 'SK%' AND o.checkout_token LIKE 'SECKILL-%';

INSERT INTO trade_compensation_task
    (id,task_type,business_no,payload_json,status,next_retry_at,created_at,updated_at)
SELECT (SELECT LEAST(COALESCE(MIN(id),0),0) FROM trade_compensation_task)-CAST(ROW_NUMBER() OVER (ORDER BY p.order_no) AS SIGNED),'CREATE_SECKILL_ORDER',p.order_no,
    JSON_OBJECT(),'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM trade_seckill_progress p WHERE p.phase IN ('PREPARED','STOCK_RESERVED');

INSERT INTO trade_compensation_task
    (id,task_type,business_no,payload_json,status,next_retry_at,created_at,updated_at)
SELECT (SELECT LEAST(COALESCE(MIN(id),0),0) FROM trade_compensation_task)-CAST(ROW_NUMBER() OVER (ORDER BY p.order_no) AS SIGNED),'RELEASE_SECKILL',p.order_no,
    p.payload_json,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM trade_seckill_progress p
LEFT JOIN trade_compensation_task t ON t.task_type='RELEASE_SECKILL' AND t.business_no=p.order_no
WHERE p.phase='CANCELLED' AND t.id IS NULL;
