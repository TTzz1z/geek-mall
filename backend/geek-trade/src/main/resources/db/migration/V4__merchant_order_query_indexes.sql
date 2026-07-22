CREATE INDEX idx_sub_order_merchant_created ON trade_sub_order(merchant_id, created_at);
CREATE INDEX idx_order_item_sub_order ON trade_order_item(sub_order_id);
