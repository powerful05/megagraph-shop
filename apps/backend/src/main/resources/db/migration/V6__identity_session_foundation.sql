-- Fail before any persistent DDL if existing usernames collapse under the new rule.
-- The temporary table disappears with the Flyway connection even when insertion fails.
CREATE TEMPORARY TABLE preflight_normalized_users (
    normalized_username VARCHAR(120) NOT NULL PRIMARY KEY
);
INSERT INTO preflight_normalized_users (normalized_username)
SELECT LOWER(TRIM(username)) FROM app_user;
DROP TABLE preflight_normalized_users;

ALTER TABLE app_user ADD COLUMN username_normalized VARCHAR(120) NOT NULL DEFAULT '';
UPDATE app_user SET username_normalized = LOWER(TRIM(username));
CREATE UNIQUE INDEX uk_app_user_username_normalized ON app_user (username_normalized);

CREATE TABLE trial_invite (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMP(3) NOT NULL,
    redeemed_at TIMESTAMP(3) NULL,
    redeemed_by BIGINT NULL,
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT uk_trial_invite_code_hash UNIQUE (code_hash),
    CONSTRAINT fk_trial_invite_user FOREIGN KEY (redeemed_by) REFERENCES app_user(id)
);

CREATE INDEX idx_trial_invite_expiry ON trial_invite (expires_at);

-- Spring Session JDBC 3.5.x standard columns; managed by Flyway, never auto-initialized.
CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BLOB NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
