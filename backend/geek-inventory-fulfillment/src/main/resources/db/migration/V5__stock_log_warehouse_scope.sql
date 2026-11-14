ALTER TABLE inventory_stock_log
    DROP INDEX uk_stock_operation,
    ADD UNIQUE KEY uk_stock_operation (merchant_id, warehouse_id, business_no, sku_id, business_type);
