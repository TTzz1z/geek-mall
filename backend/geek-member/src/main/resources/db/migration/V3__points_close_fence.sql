CREATE TABLE member_point_order_lock (
    order_no VARCHAR(64) NOT NULL PRIMARY KEY,
    closed BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO member_point_order_lock(order_no,closed)
SELECT order_no,status <> 'FROZEN' FROM member_point_reservation;
