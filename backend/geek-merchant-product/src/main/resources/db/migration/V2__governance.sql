-- Brand Phase-3: category/brand auth, flagship cert, governance columns
CREATE TABLE IF NOT EXISTS merchant_category_auth (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  category_id BIGINT NOT NULL,
  status VARCHAR(32) NOT NULL,
  valid_until DATE NULL,
  created_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_merchant_category_auth(merchant_id, category_id),
  KEY idx_mca_merchant(merchant_id, status)
);
CREATE TABLE IF NOT EXISTS merchant_brand_auth (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  brand_id BIGINT NOT NULL,
  file_url VARCHAR(500),
  status VARCHAR(32) NOT NULL,
  valid_until DATE NULL,
  created_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_merchant_brand_auth(merchant_id, brand_id),
  KEY idx_mba_merchant(merchant_id, status)
);
CREATE TABLE IF NOT EXISTS shop_flagship_cert (
  id BIGINT PRIMARY KEY,
  shop_id BIGINT NOT NULL,
  merchant_id BIGINT NOT NULL,
  brand_id BIGINT NOT NULL,
  evidence_url VARCHAR(500),
  status VARCHAR(32) NOT NULL,
  reject_reason VARCHAR(500),
  auditor_id BIGINT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  KEY idx_sfc_status(status, created_at),
  KEY idx_sfc_shop(shop_id, status)
);

SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'shop' AND COLUMN_NAME = 'cert_status'
);
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE shop ADD COLUMN cert_status VARCHAR(32) NOT NULL DEFAULT ''NONE'', ADD COLUMN cert_brand_id BIGINT NULL',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
