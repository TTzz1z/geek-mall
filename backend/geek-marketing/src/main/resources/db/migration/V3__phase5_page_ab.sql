CREATE TABLE IF NOT EXISTS cms_page_version (
  id BIGINT PRIMARY KEY,
  page_code VARCHAR(64) NOT NULL,
  version_no INT NOT NULL,
  title VARCHAR(200) NOT NULL,
  blocks_json JSON NOT NULL,
  status VARCHAR(32) NOT NULL,
  published_at TIMESTAMP(3) NULL,
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_page_version(page_code,version_no),
  KEY idx_page_status(page_code,status)
);

CREATE TABLE IF NOT EXISTS ab_experiment (
  id BIGINT PRIMARY KEY,
  experiment_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  traffic_percent INT NOT NULL DEFAULT 100,
  created_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_exp_code(experiment_code)
);

CREATE TABLE IF NOT EXISTS ab_variant (
  id BIGINT PRIMARY KEY,
  experiment_id BIGINT NOT NULL,
  variant_code VARCHAR(32) NOT NULL,
  weight INT NOT NULL,
  config_json JSON NOT NULL,
  UNIQUE KEY uk_variant(experiment_id,variant_code)
);

CREATE TABLE IF NOT EXISTS ab_assignment (
  id BIGINT PRIMARY KEY,
  experiment_id BIGINT NOT NULL,
  user_key VARCHAR(128) NOT NULL,
  variant_code VARCHAR(32) NOT NULL,
  assigned_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_ab_user(experiment_id,user_key)
);
