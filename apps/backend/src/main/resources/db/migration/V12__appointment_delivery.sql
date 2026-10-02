CREATE TABLE appointment_account_lock (
 user_id BIGINT PRIMARY KEY, CONSTRAINT fk_appointment_mutex_user FOREIGN KEY(user_id) REFERENCES app_user(id)
);
CREATE TABLE appointment_offer (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,merchant_id BIGINT NOT NULL,title VARCHAR(200) NOT NULL,description VARCHAR(2000) NOT NULL,
 price DECIMAL(12,2) NOT NULL,currency CHAR(3) NOT NULL,status VARCHAR(24) NOT NULL,version BIGINT NOT NULL DEFAULT 0,confirmed_version BIGINT NULL,
 idempotency_key VARCHAR(128) NOT NULL,payload_hash VARCHAR(64) NOT NULL,
 CONSTRAINT fk_appointment_offer_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(id),
 CONSTRAINT uk_appointment_offer_idem UNIQUE(merchant_id,idempotency_key),CONSTRAINT chk_appointment_price CHECK(price>0)
);
CREATE TABLE appointment_slot (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,offer_id BIGINT NOT NULL,merchant_id BIGINT NOT NULL,version BIGINT NOT NULL DEFAULT 0,
 start_at TIMESTAMP(3) NOT NULL,end_at TIMESTAMP(3) NOT NULL,capacity INTEGER NOT NULL,status VARCHAR(24) NOT NULL,
 idempotency_key VARCHAR(128) NOT NULL,payload_hash VARCHAR(64) NOT NULL,
 CONSTRAINT fk_appointment_slot_offer FOREIGN KEY(offer_id) REFERENCES appointment_offer(id),
 CONSTRAINT fk_appointment_slot_merchant FOREIGN KEY(merchant_id) REFERENCES merchant(id),
 CONSTRAINT uk_appointment_slot_idem UNIQUE(merchant_id,idempotency_key),
 CONSTRAINT chk_appointment_capacity CHECK(capacity BETWEEN 1 AND 50),CONSTRAINT chk_appointment_time CHECK(end_at>start_at)
);
CREATE TABLE appointment_purchase (
 order_id BIGINT PRIMARY KEY,offer_id BIGINT NOT NULL,offer_version BIGINT NOT NULL,title VARCHAR(200) NOT NULL,description VARCHAR(2000) NOT NULL,
 unit_price DECIMAL(12,2) NOT NULL,currency CHAR(3) NOT NULL,slot_id BIGINT NOT NULL,slot_version BIGINT NOT NULL,
 start_at TIMESTAMP(3) NOT NULL,end_at TIMESTAMP(3) NOT NULL,capacity INTEGER NOT NULL,
 delivery_terms VARCHAR(500) NOT NULL,refund_policy VARCHAR(500) NOT NULL,payload_hash VARCHAR(64) NOT NULL,
 CONSTRAINT fk_appointment_purchase_order FOREIGN KEY(order_id) REFERENCES sales_order(id),
 CONSTRAINT fk_appointment_purchase_offer FOREIGN KEY(offer_id) REFERENCES appointment_offer(id),
 CONSTRAINT fk_appointment_purchase_slot FOREIGN KEY(slot_id) REFERENCES appointment_slot(id)
);
CREATE TABLE appointment_reservation (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,order_id BIGINT NOT NULL,slot_id BIGINT NOT NULL,version BIGINT NOT NULL DEFAULT 0,
 status VARCHAR(24) NOT NULL,held_at TIMESTAMP(3) NOT NULL,expires_at TIMESTAMP(3) NOT NULL,
 confirmed_at TIMESTAMP(3) NULL,released_at TIMESTAMP(3) NULL,release_reason VARCHAR(64) NULL,fulfilled_at TIMESTAMP(3) NULL,
 CONSTRAINT uk_appointment_order UNIQUE(order_id),CONSTRAINT fk_appointment_reservation_order FOREIGN KEY(order_id) REFERENCES appointment_purchase(order_id),
 CONSTRAINT fk_appointment_reservation_slot FOREIGN KEY(slot_id) REFERENCES appointment_slot(id)
);
CREATE INDEX idx_appointment_capacity ON appointment_reservation(slot_id,status,expires_at);
CREATE TABLE appointment_complete_key (
 merchant_id BIGINT NOT NULL,idempotency_key VARCHAR(128) NOT NULL,order_id BIGINT NOT NULL,payload_hash VARCHAR(64) NOT NULL,
 PRIMARY KEY(merchant_id,idempotency_key),CONSTRAINT fk_appointment_complete_order FOREIGN KEY(order_id) REFERENCES sales_order(id)
);
CREATE TABLE appointment_merchant_lock (
 merchant_id BIGINT PRIMARY KEY,CONSTRAINT fk_appointment_merchant_mutex FOREIGN KEY(merchant_id) REFERENCES merchant(id)
);
