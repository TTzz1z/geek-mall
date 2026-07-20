CREATE TABLE sys_user (
    id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    user_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_username UNIQUE (username)
);

CREATE TABLE sys_role (
    id BIGINT NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(128) NOT NULL,
    data_scope VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_role_code UNIQUE (role_code)
);

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE sys_menu (
    id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    menu_name VARCHAR(128) NOT NULL,
    route_path VARCHAR(255) NULL,
    icon VARCHAR(64) NULL,
    permission_code VARCHAR(128) NULL,
    menu_type VARCHAR(16) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_menu_permission UNIQUE (permission_code)
);

CREATE TABLE sys_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id)
);

CREATE TABLE sys_refresh_token (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    device_id VARCHAR(64) NOT NULL,
    audience VARCHAR(32) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    revoked_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_user_device ON sys_refresh_token(user_id, device_id, expires_at);

CREATE TABLE sys_operation_log (
    id VARCHAR(36) NOT NULL,
    user_id BIGINT NULL,
    merchant_id BIGINT NULL,
    module VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_id VARCHAR(128) NULL,
    request_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_operation_request ON sys_operation_log(request_id);

CREATE TABLE merchant_user (
    id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    sys_user_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_merchant_sys_user UNIQUE (sys_user_id)
);

CREATE INDEX idx_merchant_user_scope ON merchant_user(merchant_id, shop_id, status);

CREATE TABLE demo_merchant_order (
    id BIGINT NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    merchant_id BIGINT NOT NULL,
    shop_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    amount DECIMAL(18,2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_demo_order_no UNIQUE (order_no)
);

CREATE INDEX idx_demo_order_tenant ON demo_merchant_order(merchant_id, shop_id, status);
