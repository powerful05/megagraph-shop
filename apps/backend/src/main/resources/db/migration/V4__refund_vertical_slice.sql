CREATE TABLE after_sale_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    request_idempotency_key VARCHAR(128) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(24) NOT NULL,
    resolution_idempotency_key VARCHAR(128) NULL,
    merchant_note VARCHAR(500) NULL,
    requested_at TIMESTAMP(3) NOT NULL,
    resolved_at TIMESTAMP(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_after_sale_order FOREIGN KEY (order_id) REFERENCES sales_order(id),
    CONSTRAINT fk_after_sale_user FOREIGN KEY (user_id) REFERENCES app_user(id),
    CONSTRAINT fk_after_sale_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT uk_after_sale_order UNIQUE (order_id),
    CONSTRAINT uk_after_sale_user_idempotency UNIQUE (user_id, request_idempotency_key),
    CONSTRAINT uk_after_sale_merchant_resolution UNIQUE (merchant_id, resolution_idempotency_key)
);

CREATE INDEX idx_after_sale_merchant_status ON after_sale_request (merchant_id, status, requested_at);

CREATE TABLE refund_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    after_sale_id BIGINT NOT NULL,
    order_id BIGINT NOT NULL,
    payment_order_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_refund_id VARCHAR(80) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL,
    refunded_at TIMESTAMP(3) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_refund_after_sale FOREIGN KEY (after_sale_id) REFERENCES after_sale_request(id),
    CONSTRAINT fk_refund_order FOREIGN KEY (order_id) REFERENCES sales_order(id),
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_order_id) REFERENCES payment_order(id),
    CONSTRAINT uk_refund_after_sale UNIQUE (after_sale_id),
    CONSTRAINT uk_refund_provider_id UNIQUE (provider, provider_refund_id),
    CONSTRAINT chk_refund_amount CHECK (amount > 0)
);
