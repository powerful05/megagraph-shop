CREATE TABLE checkout_group (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    group_no VARCHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    currency CHAR(3) NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_checkout_group_no UNIQUE (group_no),
    CONSTRAINT uk_checkout_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_checkout_user FOREIGN KEY (user_id) REFERENCES app_user(id)
);
ALTER TABLE sales_order ADD COLUMN checkout_group_id BIGINT NULL;
ALTER TABLE sales_order ADD COLUMN test_order BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sales_order ADD CONSTRAINT fk_order_checkout_group FOREIGN KEY (checkout_group_id) REFERENCES checkout_group(id);
CREATE INDEX idx_order_checkout_group ON sales_order(checkout_group_id);
ALTER TABLE sales_order_item ADD COLUMN product_version BIGINT NULL;
ALTER TABLE sales_order_item ADD COLUMN supply_details TEXT NULL;
