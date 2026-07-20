CREATE TABLE merchant (
 id BIGINT PRIMARY KEY, merchant_type VARCHAR(32) NOT NULL, company_name VARCHAR(160) NOT NULL,
 credit_code VARCHAR(64) NOT NULL UNIQUE, status VARCHAR(32) NOT NULL, version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE merchant_application (
 id BIGINT PRIMARY KEY, application_no VARCHAR(32) NOT NULL UNIQUE, applicant_user_id BIGINT NOT NULL,
 merchant_id BIGINT NULL, company_name VARCHAR(160) NOT NULL, credit_code VARCHAR(64) NOT NULL,
 contact_name VARCHAR(64) NOT NULL, contact_mobile VARCHAR(32) NOT NULL, categories_json TEXT NOT NULL,
 status VARCHAR(32) NOT NULL, reject_reason VARCHAR(500), version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE merchant_qualification (
 id BIGINT PRIMARY KEY, application_id BIGINT NOT NULL, qualification_type VARCHAR(64) NOT NULL,
 file_url VARCHAR(500) NOT NULL, valid_until DATE NULL, created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE shop (
 id BIGINT PRIMARY KEY, merchant_id BIGINT NOT NULL, shop_name VARCHAR(128) NOT NULL, shop_type VARCHAR(32) NOT NULL,
 logo_url VARCHAR(500), announcement VARCHAR(500), score DECIMAL(3,2) NOT NULL DEFAULT 5.00,
 status VARCHAR(32) NOT NULL, version INT NOT NULL DEFAULT 0, created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 KEY idx_shop_merchant(merchant_id,status)
);
CREATE TABLE merchant_subaccount (
 id BIGINT PRIMARY KEY, merchant_id BIGINT NOT NULL, shop_id BIGINT NOT NULL, username VARCHAR(64) NOT NULL,
 role_code VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, created_at TIMESTAMP(3) NOT NULL,
 UNIQUE KEY uk_subaccount_merchant_user(merchant_id,username)
);
CREATE TABLE product_category (
 id BIGINT PRIMARY KEY, parent_id BIGINT NULL, category_name VARCHAR(96) NOT NULL, level INT NOT NULL,
 path VARCHAR(255) NOT NULL, sort_order INT NOT NULL DEFAULT 0, status VARCHAR(32) NOT NULL
);
CREATE TABLE product_brand (
 id BIGINT PRIMARY KEY, brand_name VARCHAR(96) NOT NULL UNIQUE, logo_url VARCHAR(500), description VARCHAR(500), status VARCHAR(32) NOT NULL
);
CREATE TABLE product_category_brand (category_id BIGINT NOT NULL, brand_id BIGINT NOT NULL, PRIMARY KEY(category_id,brand_id));
CREATE TABLE product_attr_group (
 id BIGINT PRIMARY KEY, category_id BIGINT NOT NULL, group_name VARCHAR(96) NOT NULL, sort_order INT NOT NULL DEFAULT 0
);
CREATE TABLE product_attr (
 id BIGINT PRIMARY KEY, group_id BIGINT NOT NULL, attr_name VARCHAR(96) NOT NULL, attr_type VARCHAR(32) NOT NULL,
 unit VARCHAR(32), searchable TINYINT NOT NULL DEFAULT 0, comparable TINYINT NOT NULL DEFAULT 0, sale_attr TINYINT NOT NULL DEFAULT 0
);
CREATE TABLE product_attr_option (
 id BIGINT PRIMARY KEY, attr_id BIGINT NOT NULL, option_value VARCHAR(128) NOT NULL, sort_order INT NOT NULL DEFAULT 0
);
CREATE TABLE product_spu (
 id BIGINT PRIMARY KEY, merchant_id BIGINT NOT NULL, shop_id BIGINT NOT NULL, source_type VARCHAR(32) NOT NULL,
 category_id BIGINT NOT NULL, brand_id BIGINT NOT NULL, title VARCHAR(255) NOT NULL, subtitle VARCHAR(500),
 audit_status VARCHAR(32) NOT NULL, sale_status VARCHAR(32) NOT NULL, min_price DECIMAL(18,2) NOT NULL DEFAULT 0,
 max_price DECIMAL(18,2) NOT NULL DEFAULT 0, cover_url VARCHAR(500), sales_count BIGINT NOT NULL DEFAULT 0,
 rating DECIMAL(3,2) NOT NULL DEFAULT 5.00, version INT NOT NULL DEFAULT 0,
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL,
 KEY idx_spu_shop_sale(shop_id,sale_status,updated_at), KEY idx_spu_category_brand(category_id,brand_id,sale_status)
);
CREATE TABLE product_spu_detail (
 spu_id BIGINT PRIMARY KEY, description_html TEXT, package_list TEXT, service_desc TEXT
);
CREATE TABLE product_spu_attr_value (
 id BIGINT PRIMARY KEY, spu_id BIGINT NOT NULL, attr_id BIGINT NOT NULL, value_text VARCHAR(500), value_num DECIMAL(18,4), option_id BIGINT,
 UNIQUE KEY uk_spu_attr(spu_id,attr_id)
);
CREATE TABLE product_sku (
 id BIGINT PRIMARY KEY, spu_id BIGINT NOT NULL, sku_code VARCHAR(64) NOT NULL UNIQUE, barcode VARCHAR(64),
 sale_price DECIMAL(18,2) NOT NULL, market_price DECIMAL(18,2) NOT NULL, cost_price DECIMAL(18,2) NOT NULL,
 weight INT NOT NULL DEFAULT 0, volume INT NOT NULL DEFAULT 0, status VARCHAR(32) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL, updated_at TIMESTAMP(3) NOT NULL, KEY idx_sku_spu(spu_id,status)
);
CREATE TABLE product_sku_sale_attr (
 id BIGINT PRIMARY KEY, sku_id BIGINT NOT NULL, attr_id BIGINT NOT NULL, option_id BIGINT, value_text VARCHAR(128) NOT NULL
);
CREATE TABLE product_document (
 id BIGINT PRIMARY KEY, spu_id BIGINT NOT NULL, doc_type VARCHAR(32) NOT NULL, file_url VARCHAR(500) NOT NULL,
 parse_status VARCHAR(32) NOT NULL, version INT NOT NULL DEFAULT 1, created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE product_audit_record (
 id BIGINT PRIMARY KEY, spu_id BIGINT NOT NULL, auditor_id BIGINT NOT NULL, result VARCHAR(32) NOT NULL,
 reason VARCHAR(500), created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE product_operation_log (
 id BIGINT PRIMARY KEY, spu_id BIGINT NOT NULL, action VARCHAR(64) NOT NULL, operator_id BIGINT NOT NULL,
 request_id VARCHAR(64), created_at TIMESTAMP(3) NOT NULL
);
CREATE TABLE member_favorite_product (user_id BIGINT NOT NULL, spu_id BIGINT NOT NULL, created_at TIMESTAMP(3) NOT NULL, PRIMARY KEY(user_id,spu_id));
CREATE TABLE member_follow_shop (user_id BIGINT NOT NULL, shop_id BIGINT NOT NULL, created_at TIMESTAMP(3) NOT NULL, PRIMARY KEY(user_id,shop_id));
CREATE TABLE member_browse_history (user_id BIGINT NOT NULL, spu_id BIGINT NOT NULL, viewed_at TIMESTAMP(3) NOT NULL, PRIMARY KEY(user_id,spu_id));
CREATE TABLE event_outbox (
 id BIGINT PRIMARY KEY, event_id VARCHAR(64) NOT NULL UNIQUE, aggregate_type VARCHAR(64) NOT NULL, aggregate_id VARCHAR(64) NOT NULL,
 event_type VARCHAR(128) NOT NULL, payload_json TEXT NOT NULL, status VARCHAR(32) NOT NULL, retry_count INT NOT NULL DEFAULT 0,
 next_retry_at TIMESTAMP(3) NULL, created_at TIMESTAMP(3) NOT NULL, published_at TIMESTAMP(3) NULL,
 KEY idx_outbox_dispatch(status,next_retry_at,created_at)
);
