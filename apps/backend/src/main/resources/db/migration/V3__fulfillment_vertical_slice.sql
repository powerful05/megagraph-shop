CREATE TABLE shipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    merchant_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    carrier_name VARCHAR(80) NOT NULL,
    tracking_no VARCHAR(80) NOT NULL,
    status VARCHAR(24) NOT NULL,
    shipped_at TIMESTAMP(3) NOT NULL,
    delivered_at TIMESTAMP(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_id) REFERENCES sales_order(id),
    CONSTRAINT fk_shipment_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
    CONSTRAINT uk_shipment_order UNIQUE (order_id),
    CONSTRAINT uk_shipment_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_shipment_tracking ON shipment (carrier_name, tracking_no);
