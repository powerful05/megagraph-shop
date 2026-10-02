CREATE TABLE guide_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    public_id VARCHAR(36) NOT NULL,
    owner_user_id BIGINT NOT NULL,
    title VARCHAR(80) NOT NULL,
    next_sequence BIGINT NOT NULL,
    event_version BIGINT NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_guide_conversation_public UNIQUE (public_id),
    CONSTRAINT fk_guide_conversation_owner FOREIGN KEY (owner_user_id) REFERENCES app_user(id)
);
CREATE INDEX idx_guide_conversation_owner_updated ON guide_conversation (owner_user_id, updated_at, id);

CREATE TABLE guide_turn_request (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id VARCHAR(36) NOT NULL,
    conversation_id BIGINT NOT NULL,
    turn_sequence BIGINT NOT NULL,
    user_content VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt INT NOT NULL,
    lease_expires_at TIMESTAMP(3) NOT NULL,
    result_json LONGTEXT NULL,
    error_code VARCHAR(80) NULL,
    error_message VARCHAR(200) NULL,
    model_version VARCHAR(120) NOT NULL,
    protocol_version VARCHAR(80) NOT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_guide_request_key UNIQUE (conversation_id, request_id),
    CONSTRAINT uk_guide_request_sequence UNIQUE (conversation_id, turn_sequence),
    CONSTRAINT fk_guide_request_conversation FOREIGN KEY (conversation_id) REFERENCES guide_conversation(id)
);
CREATE INDEX idx_guide_request_status ON guide_turn_request (conversation_id, status);
