CREATE TABLE IF NOT EXISTS member_user_address (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  receiver VARCHAR(64) NOT NULL,
  mobile VARCHAR(32) NOT NULL,
  region_code VARCHAR(32) NOT NULL,
  region_name VARCHAR(128) NOT NULL,
  detail VARCHAR(255) NOT NULL,
  is_default TINYINT(1) NOT NULL DEFAULT 0,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  KEY idx_address_user(user_id, is_default)
);
