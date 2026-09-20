CREATE TABLE payment_channel_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    provider_event_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    provider_resource_id VARCHAR(120) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    payload_text TEXT NOT NULL,
    occurred_at TIMESTAMP(3) NOT NULL,
    received_at TIMESTAMP(3) NOT NULL,
    processing_status VARCHAR(24) NOT NULL,
    processed_at TIMESTAMP(3) NULL,
    matched_payment_order_id BIGINT NULL,
    matched_refund_order_id BIGINT NULL,
    failure_code VARCHAR(80) NULL,
    conflict_count INTEGER NOT NULL DEFAULT 0,
    last_conflict_digest CHAR(64) NULL,
    last_conflict_at TIMESTAMP(3) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_payment_channel_event UNIQUE (provider, provider_event_id),
    CONSTRAINT fk_channel_event_payment FOREIGN KEY (matched_payment_order_id) REFERENCES payment_order(id),
    CONSTRAINT fk_channel_event_refund FOREIGN KEY (matched_refund_order_id) REFERENCES refund_order(id)
);

CREATE INDEX idx_channel_event_resource
    ON payment_channel_event (provider, resource_type, provider_resource_id, received_at);
CREATE INDEX idx_channel_event_status
    ON payment_channel_event (processing_status, received_at);

ALTER TABLE payment_order ADD COLUMN last_channel_event_id BIGINT NULL;
ALTER TABLE payment_order ADD COLUMN status_changed_at TIMESTAMP(3) NULL;
ALTER TABLE payment_order ADD CONSTRAINT fk_payment_last_channel_event
    FOREIGN KEY (last_channel_event_id) REFERENCES payment_channel_event(id);

ALTER TABLE refund_order MODIFY COLUMN provider_refund_id VARCHAR(80) NULL;
ALTER TABLE refund_order MODIFY COLUMN refunded_at TIMESTAMP(3) NULL;
ALTER TABLE refund_order ADD COLUMN failure_code VARCHAR(80) NULL;
ALTER TABLE refund_order ADD COLUMN error_summary VARCHAR(500) NULL;
ALTER TABLE refund_order ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE refund_order ADD COLUMN updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
ALTER TABLE refund_order ADD COLUMN last_channel_event_id BIGINT NULL;
ALTER TABLE refund_order ADD CONSTRAINT fk_refund_last_channel_event
    FOREIGN KEY (last_channel_event_id) REFERENCES payment_channel_event(id);

CREATE TABLE refund_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_order_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    attempt_no INTEGER NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_refund_attempt_refund FOREIGN KEY (refund_order_id) REFERENCES refund_order(id),
    CONSTRAINT uk_refund_attempt_idempotency UNIQUE (refund_order_id, idempotency_key),
    CONSTRAINT uk_refund_attempt_number UNIQUE (refund_order_id, attempt_no)
);

CREATE TABLE payment_channel_command (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    provider_event_id VARCHAR(120) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    resource_type VARCHAR(16) NOT NULL,
    provider_resource_id VARCHAR(120) NULL,
    payment_order_id BIGINT NULL,
    refund_order_id BIGINT NULL,
    refund_attempt_id BIGINT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(24) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(3) NOT NULL,
    claimed_at TIMESTAMP(3) NULL,
    last_error VARCHAR(500) NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    completed_at TIMESTAMP(3) NULL,
    CONSTRAINT uk_channel_command_event UNIQUE (provider, provider_event_id),
    CONSTRAINT fk_channel_command_payment FOREIGN KEY (payment_order_id) REFERENCES payment_order(id),
    CONSTRAINT fk_channel_command_refund FOREIGN KEY (refund_order_id) REFERENCES refund_order(id),
    CONSTRAINT fk_channel_command_attempt FOREIGN KEY (refund_attempt_id) REFERENCES refund_attempt(id)
);

CREATE INDEX idx_channel_command_claim
    ON payment_channel_command (status, next_attempt_at, created_at);
