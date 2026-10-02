ALTER TABLE sales_order ADD COLUMN delivery_type VARCHAR(24) NOT NULL DEFAULT 'PHYSICAL';
CREATE TABLE digital_asset (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, merchant_id BIGINT NOT NULL, asset_version BIGINT NOT NULL,
 content_hash VARCHAR(64) NOT NULL, content_type VARCHAR(120) NOT NULL, content_text TEXT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_digital_asset_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id)
);
CREATE TABLE delivery_offer (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, merchant_id BIGINT NOT NULL, delivery_type VARCHAR(24) NOT NULL,
 title VARCHAR(200) NOT NULL, description VARCHAR(2000) NOT NULL, price DECIMAL(12,2) NOT NULL,
 currency CHAR(3) NOT NULL, status VARCHAR(24) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 confirmed_version BIGINT NULL, asset_id BIGINT NOT NULL, idempotency_key VARCHAR(128) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_offer_merchant FOREIGN KEY (merchant_id) REFERENCES merchant(id),
 CONSTRAINT fk_offer_asset FOREIGN KEY (asset_id) REFERENCES digital_asset(id),
 CONSTRAINT uk_offer_idem UNIQUE (merchant_id,idempotency_key), CONSTRAINT chk_offer_price CHECK(price>0)
);
CREATE INDEX idx_delivery_public ON delivery_offer(status,merchant_id);
CREATE TABLE delivery_purchase (
 order_id BIGINT PRIMARY KEY, offer_id BIGINT NOT NULL, offer_version BIGINT NOT NULL,
 title VARCHAR(200) NOT NULL, description VARCHAR(2000) NOT NULL, unit_price DECIMAL(12,2) NOT NULL,
 currency CHAR(3) NOT NULL, asset_id BIGINT NOT NULL, asset_version BIGINT NOT NULL,
 asset_hash VARCHAR(64) NOT NULL, delivery_terms VARCHAR(500) NOT NULL, refund_policy VARCHAR(500) NOT NULL,
 availability_hours INTEGER NOT NULL, payload_hash VARCHAR(64) NOT NULL,
 CONSTRAINT fk_purchase_order FOREIGN KEY (order_id) REFERENCES sales_order(id),
 CONSTRAINT fk_purchase_offer FOREIGN KEY (offer_id) REFERENCES delivery_offer(id),
 CONSTRAINT fk_purchase_asset FOREIGN KEY (asset_id) REFERENCES digital_asset(id)
);
CREATE TABLE digital_entitlement (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, order_id BIGINT NOT NULL, status VARCHAR(24) NOT NULL,
 available_at TIMESTAMP(3) NOT NULL, expires_at TIMESTAMP(3) NOT NULL,
 claimed_at TIMESTAMP(3) NULL, revoked_at TIMESTAMP(3) NULL, version BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT uk_entitlement_order UNIQUE(order_id),
 CONSTRAINT fk_entitlement_purchase FOREIGN KEY(order_id) REFERENCES delivery_purchase(order_id)
);
CREATE TABLE digital_claim_key (
 user_id BIGINT NOT NULL, idempotency_key VARCHAR(128) NOT NULL, order_id BIGINT NOT NULL,
 PRIMARY KEY(user_id,idempotency_key), CONSTRAINT fk_claim_order FOREIGN KEY(order_id) REFERENCES sales_order(id)
);
