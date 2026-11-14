INSERT INTO trade_compensation_task
    (id, task_type, business_no, payload_json, status, next_retry_at, created_at, updated_at)
SELECT -ROW_NUMBER() OVER (ORDER BY steps.business_no, steps.task_type),
    steps.task_type, steps.business_no, JSON_OBJECT(), 'PENDING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM (
    SELECT 'DEDUCT_STOCK' AS task_type, order_no AS business_no FROM trade_order
        WHERE paid_payment_id IS NOT NULL AND status IN ('PAID','PARTIAL_SHIPPED','SHIPPED','COMPLETED')
            AND inventory_status <> 'DEDUCTED'
    UNION ALL
    SELECT 'CREATE_FULFILLMENT', order_no FROM trade_order
        WHERE paid_payment_id IS NOT NULL AND status IN ('PAID','PARTIAL_SHIPPED','SHIPPED','COMPLETED')
    UNION ALL
    SELECT 'SETTLE_ASSETS', order_no FROM trade_order
        WHERE paid_payment_id IS NOT NULL AND status IN ('PAID','PARTIAL_SHIPPED','SHIPPED','COMPLETED')
    UNION ALL
    SELECT 'ORDER_SETTLEMENT', order_no FROM trade_order
        WHERE paid_payment_id IS NOT NULL AND status='COMPLETED'
    UNION ALL
    SELECT 'RESERVE_STOCK', order_no FROM trade_order
        WHERE status IN ('RESERVING','RESERVATION_RETRY') AND order_no NOT LIKE 'SK%'
    UNION ALL
    SELECT 'RELEASE_STOCK', order_no FROM trade_order
        WHERE status='CANCELLED' AND inventory_status='RELEASE_PENDING'
    UNION ALL
    SELECT 'RELEASE_ASSETS', order_no FROM trade_order
        WHERE status='CANCELLED'
    UNION ALL
    SELECT 'REFUND', aftersale_id FROM trade_refund WHERE status='PROCESSING'
) steps
LEFT JOIN trade_compensation_task task ON task.task_type=steps.task_type AND task.business_no=steps.business_no
WHERE task.id IS NULL;

UPDATE trade_compensation_task
SET status='RETRY', retry_count=0, next_retry_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
WHERE status='FAILED' AND task_type IN
    ('DEDUCT_STOCK','CREATE_FULFILLMENT','SETTLE_ASSETS','ORDER_SETTLEMENT','RESERVE_STOCK','RELEASE_STOCK','RELEASE_ASSETS','REFUND');
