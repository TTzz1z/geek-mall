CREATE TABLE trade_late_payment_return (
    payment_no VARCHAR(64) NOT NULL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    channel VARCHAR(32) NOT NULL,
    channel_trade_no VARCHAR(96) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    return_no VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    channel_return_no VARCHAR(96) NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    created_at DATETIME(3) NOT NULL,
    updated_at DATETIME(3) NOT NULL,
    succeeded_at DATETIME(3) NULL,
    KEY idx_late_return_status(status,created_at)
);
CREATE TABLE trade_simulated_payment_return (
    payment_no VARCHAR(64) NOT NULL PRIMARY KEY,
    channel_trade_no VARCHAR(96) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    channel_return_no VARCHAR(96) NOT NULL UNIQUE,
    created_at DATETIME(3) NOT NULL
);

-- Import each historical late payment independently. Do not reinterpret historical refunds.
INSERT INTO trade_late_payment_return(payment_no,order_no,channel,channel_trade_no,amount,return_no,status,created_at,updated_at)
SELECT p.payment_no,o.order_no,p.channel,COALESCE(p.channel_trade_no,''),p.amount,CONCAT('LPR',p.id),
    CASE WHEN p.channel='SIMULATED' AND p.channel_trade_no IS NOT NULL THEN 'PENDING' ELSE 'MANUAL' END,
    p.created_at,CURRENT_TIMESTAMP(3)
FROM trade_payment p JOIN trade_order o ON o.id=p.order_id WHERE p.status='LATE_SUCCESS';

-- Supersede order-scoped late-payment tasks; they cannot identify an individual return.
UPDATE trade_compensation_task t JOIN trade_order o ON o.order_no=t.business_no
SET t.status='SUCCESS',t.last_error='SUPERSEDED_BY_PER_PAYMENT_RETURN',t.lease_token=NULL,t.lease_until=NULL,t.updated_at=CURRENT_TIMESTAMP
WHERE t.task_type='PAYMENT_AFTER_CLOSE';

INSERT INTO trade_compensation_task(id,task_type,business_no,payload_json,status,next_retry_at,created_at,updated_at)
SELECT (SELECT base_id FROM (SELECT LEAST(COALESCE(MIN(id),0),0) AS base_id FROM trade_compensation_task) migration_base)
    - CAST(ROW_NUMBER() OVER (ORDER BY r.payment_no) AS SIGNED),'PAYMENT_AFTER_CLOSE',r.payment_no,JSON_OBJECT(),r.status,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
FROM trade_late_payment_return r
LEFT JOIN trade_compensation_task t ON t.task_type='PAYMENT_AFTER_CLOSE' AND t.business_no=r.payment_no
WHERE t.id IS NULL;

-- Reopen old successful release tasks once so every cancelled order writes the new close fences.
UPDATE trade_compensation_task t JOIN trade_order o ON o.order_no=t.business_no
SET t.status='RETRY',t.next_retry_at=CURRENT_TIMESTAMP,t.lease_token=NULL,t.lease_until=NULL,t.updated_at=CURRENT_TIMESTAMP
WHERE o.status='CANCELLED' AND t.task_type IN ('RELEASE_STOCK','RELEASE_ASSETS');

-- Inspection-only reconciliation view: historical successful refunds require operator verification.
CREATE VIEW trade_historical_refund_reconciliation AS
SELECT r.refund_no,o.order_no,p.payment_no,r.amount,r.channel_refund_no,r.succeeded_at
FROM trade_refund r JOIN trade_order o ON o.id=r.order_id
LEFT JOIN trade_payment p ON p.id=r.payment_id WHERE r.status='SUCCESS';
