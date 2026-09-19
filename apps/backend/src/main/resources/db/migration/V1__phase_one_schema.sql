CREATE TABLE app_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(120) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_app_user_username UNIQUE (username)
);

CREATE TABLE merchant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);

CREATE TABLE merchant_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_merchant_user_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT fk_merchant_user_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT uk_merchant_user_membership UNIQUE (merchant_id, user_id)
);

CREATE TABLE category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    parent_id BIGINT NULL,
    name VARCHAR(120) NOT NULL,
    level_no INTEGER NOT NULL,
    visible BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_category_parent FOREIGN KEY (parent_id) REFERENCES category(id)
);

CREATE TABLE product (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    category_id BIGINT NULL,
    title VARCHAR(200) NULL,
    subtitle VARCHAR(300) NULL,
    draft_status VARCHAR(32) NOT NULL,
    publish_status VARCHAR(32) NOT NULL,
    confirmed_by BIGINT NULL,
    confirmed_at TIMESTAMP(3) NULL,
    published_at TIMESTAMP(3) NULL,
    off_shelf_at TIMESTAMP(3) NULL,
    deleted_at TIMESTAMP(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_product_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT fk_product_category FOREIGN KEY (category_id) REFERENCES category(id),
    CONSTRAINT fk_product_confirmer FOREIGN KEY (confirmed_by) REFERENCES app_user(id)
);

CREATE INDEX idx_product_merchant_status
    ON product (merchant_id, publish_status, deleted_at, updated_at);
CREATE INDEX idx_product_public
    ON product (publish_status, deleted_at, published_at);

CREATE TABLE product_image (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL,
    byte_size BIGINT NOT NULL,
    image_role VARCHAR(24) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_product_image_product FOREIGN KEY (product_id) REFERENCES product(id)
);

CREATE INDEX idx_product_image_product ON product_image (product_id, sort_order);

CREATE TABLE product_sku (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    sku_name VARCHAR(200) NOT NULL,
    price DECIMAL(12, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    available_stock INTEGER NOT NULL,
    status VARCHAR(16) NOT NULL,
    deleted_at TIMESTAMP(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_product_sku_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT uk_product_sku_code UNIQUE (merchant_id, sku_code),
    CONSTRAINT chk_product_sku_price CHECK (price >= 0),
    CONSTRAINT chk_product_sku_stock CHECK (available_stock >= 0)
);

CREATE INDEX idx_product_sku_sellable
    ON product_sku (product_id, status, deleted_at, available_stock, price);

CREATE TABLE ingestion_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id BIGINT NOT NULL,
    product_id BIGINT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    error_code VARCHAR(64) NULL,
    error_summary VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ingestion_job_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT uk_ingestion_idempotency UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_ingestion_merchant_created ON ingestion_job (merchant_id, created_at);
CREATE INDEX idx_ingestion_status_created ON ingestion_job (status, created_at);

CREATE TABLE ingestion_step (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    result_json TEXT NULL,
    error_code VARCHAR(64) NULL,
    error_summary VARCHAR(500) NULL,
    started_at TIMESTAMP(3) NULL,
    finished_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ingestion_step_job FOREIGN KEY (job_id) REFERENCES ingestion_job(id),
    CONSTRAINT uk_ingestion_job_step UNIQUE (job_id, step_type)
);

CREATE TABLE outbox_event (
    event_id VARCHAR(36) PRIMARY KEY,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at TIMESTAMP(3) NULL,
    CONSTRAINT uk_outbox_aggregate_event
        UNIQUE (aggregate_type, aggregate_id, aggregate_version, event_type)
);

CREATE INDEX idx_outbox_claim ON outbox_event (status, next_attempt_at, created_at);

