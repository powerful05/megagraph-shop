CREATE TABLE shopping_cart (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_shopping_cart_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT uk_shopping_cart_user UNIQUE (user_id)
);

CREATE TABLE shopping_cart_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cart_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_cart_item_cart FOREIGN KEY (cart_id) REFERENCES shopping_cart(id),
    CONSTRAINT fk_cart_item_sku FOREIGN KEY (sku_id) REFERENCES product_sku(id),
    CONSTRAINT uk_cart_item_sku UNIQUE (cart_id, sku_id),
    CONSTRAINT chk_cart_item_quantity CHECK (quantity > 0)
);

CREATE TABLE sales_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(40) NOT NULL,
    user_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    currency CHAR(3) NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    recipient_name VARCHAR(80) NOT NULL,
    recipient_phone VARCHAR(32) NOT NULL,
    shipping_address VARCHAR(500) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    paid_at TIMESTAMP(3) NULL,
    cancelled_at TIMESTAMP(3) NULL,
    cancel_reason VARCHAR(160) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_sales_order_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_sales_order_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT uk_sales_order_no UNIQUE (order_no),
    CONSTRAINT uk_sales_order_user_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT chk_sales_order_amount CHECK (total_amount >= 0)
);

CREATE INDEX idx_sales_order_user_created ON sales_order (user_id, created_at);
CREATE INDEX idx_sales_order_merchant_created ON sales_order (merchant_id, created_at);
CREATE INDEX idx_sales_order_expiry ON sales_order (status, expires_at);

CREATE TABLE sales_order_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    product_title VARCHAR(200) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    sku_name VARCHAR(200) NOT NULL,
    unit_price DECIMAL(12, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    quantity INTEGER NOT NULL,
    line_amount DECIMAL(12, 2) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_order_item_order FOREIGN KEY (order_id) REFERENCES sales_order(id),
    CONSTRAINT fk_order_item_product FOREIGN KEY (product_id) REFERENCES product(id),
    CONSTRAINT fk_order_item_sku FOREIGN KEY (sku_id) REFERENCES product_sku(id),
    CONSTRAINT chk_order_item_quantity CHECK (quantity > 0),
    CONSTRAINT chk_order_item_amount CHECK (unit_price >= 0 AND line_amount >= 0)
);

CREATE INDEX idx_order_item_order ON sales_order_item (order_id, id);

CREATE TABLE stock_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    sku_id BIGINT NOT NULL,
    quantity INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_stock_reservation_order FOREIGN KEY (order_id) REFERENCES sales_order(id),
    CONSTRAINT fk_stock_reservation_sku FOREIGN KEY (sku_id) REFERENCES product_sku(id),
    CONSTRAINT uk_stock_reservation_order_sku UNIQUE (order_id, sku_id),
    CONSTRAINT chk_stock_reservation_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_stock_reservation_expiry ON stock_reservation (status, expires_at);

CREATE TABLE payment_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_payment_id VARCHAR(80) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL,
    finished_at TIMESTAMP(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_payment_order_order FOREIGN KEY (order_id) REFERENCES sales_order(id),
    CONSTRAINT uk_payment_order_order UNIQUE (order_id),
    CONSTRAINT uk_payment_provider_id UNIQUE (provider, provider_payment_id),
    CONSTRAINT chk_payment_order_amount CHECK (amount >= 0)
);

CREATE TABLE payment_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_order_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    requested_result VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_payment_attempt_payment FOREIGN KEY (payment_order_id) REFERENCES payment_order(id),
    CONSTRAINT uk_payment_attempt_idempotency UNIQUE (payment_order_id, idempotency_key)
);
