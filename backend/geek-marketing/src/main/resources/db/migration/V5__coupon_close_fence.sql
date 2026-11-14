CREATE TABLE marketing_coupon_order_lock (
    order_no VARCHAR(64) NOT NULL PRIMARY KEY,
    closed BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO marketing_coupon_order_lock(order_no,closed)
SELECT order_no,status <> 'FROZEN' FROM marketing_coupon_reservation;
