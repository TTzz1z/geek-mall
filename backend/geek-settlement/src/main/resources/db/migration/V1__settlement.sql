CREATE TABLE settlement_commission_rule (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NULL,
  category_id BIGINT NULL,
  rate DECIMAL(8,4) NOT NULL,
  start_at TIMESTAMP(3) NOT NULL,
  end_at TIMESTAMP(3) NULL,
  status VARCHAR(32) NOT NULL
);

CREATE TABLE settlement_pending_item (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  order_item_id BIGINT NOT NULL,
  income DECIMAL(18,2) NOT NULL,
  commission DECIMAL(18,2) NOT NULL,
  refund_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  settle_at TIMESTAMP(3) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_pending_item(order_item_id),
  KEY idx_pending_merchant(merchant_id,status)
);

CREATE TABLE settlement_statement (
  id BIGINT PRIMARY KEY,
  settlement_no VARCHAR(64) NOT NULL,
  merchant_id BIGINT NOT NULL,
  period_start DATE NOT NULL,
  period_end DATE NOT NULL,
  income DECIMAL(18,2) NOT NULL,
  commission DECIMAL(18,2) NOT NULL,
  refund DECIMAL(18,2) NOT NULL,
  adjustment DECIMAL(18,2) NOT NULL DEFAULT 0,
  payable DECIMAL(18,2) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  confirmed_at TIMESTAMP(3) NULL,
  UNIQUE KEY uk_settlement_no(settlement_no),
  KEY idx_statement_merchant(merchant_id,status)
);

CREATE TABLE settlement_statement_item (
  id BIGINT PRIMARY KEY,
  statement_id BIGINT NOT NULL,
  source_type VARCHAR(32) NOT NULL,
  source_id VARCHAR(64) NOT NULL,
  amount DECIMAL(18,2) NOT NULL
);

CREATE TABLE settlement_adjustment (
  id BIGINT PRIMARY KEY,
  merchant_id BIGINT NOT NULL,
  adjustment_type VARCHAR(32) NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL
);

CREATE TABLE withdrawal_request (
  id BIGINT PRIMARY KEY,
  withdrawal_no VARCHAR(64) NOT NULL,
  merchant_id BIGINT NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  status VARCHAR(32) NOT NULL,
  bank_snapshot VARCHAR(255) NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  approved_at TIMESTAMP(3) NULL,
  UNIQUE KEY uk_withdrawal_no(withdrawal_no),
  KEY idx_withdraw_merchant(merchant_id,status)
);

CREATE TABLE merchant_deposit (
  merchant_id BIGINT PRIMARY KEY,
  total_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  frozen_amount DECIMAL(18,2) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL
);

CREATE TABLE invoice_title (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  title_type VARCHAR(32) NOT NULL,
  title VARCHAR(200) NOT NULL,
  tax_no VARCHAR(64) NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL
);

CREATE TABLE invoice_application (
  id BIGINT PRIMARY KEY,
  application_no VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  order_no VARCHAR(64) NOT NULL,
  title_id BIGINT NOT NULL,
  amount DECIMAL(18,2) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_invoice_app(application_no),
  KEY idx_invoice_user(user_id,status)
);

CREATE TABLE analytics_metric_daily (
  id BIGINT PRIMARY KEY,
  metric_date DATE NOT NULL,
  metric_code VARCHAR(64) NOT NULL,
  dimension VARCHAR(64) NOT NULL DEFAULT 'ALL',
  metric_value DECIMAL(18,4) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  UNIQUE KEY uk_metric(metric_date,metric_code,dimension)
);
