ALTER TABLE ingestion_job ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE ingestion_job ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ingestion_job ADD COLUMN retry_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE ingestion_job ADD COLUMN lease_until TIMESTAMP(3) NULL;
ALTER TABLE ingestion_job ADD COLUMN next_attempt_at TIMESTAMP(3) NULL;
ALTER TABLE ingestion_job ADD COLUMN input_hash VARCHAR(64) NULL;
ALTER TABLE ingestion_job ADD COLUMN payload_hash VARCHAR(64) NULL;
ALTER TABLE ingestion_job ADD COLUMN storage_key VARCHAR(500) NULL;
ALTER TABLE ingestion_job ADD COLUMN content_type VARCHAR(120) NULL;
ALTER TABLE ingestion_job ADD COLUMN byte_size BIGINT NULL;
ALTER TABLE ingestion_job ADD COLUMN mock_scenario VARCHAR(24) NOT NULL DEFAULT 'success';
CREATE UNIQUE INDEX uk_ingestion_product ON ingestion_job (product_id);
CREATE INDEX idx_ingestion_due ON ingestion_job (status, next_attempt_at, lease_until);
CREATE TABLE ingestion_artifact (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 job_id BIGINT NOT NULL,
 merchant_id BIGINT NOT NULL,
 attempt INTEGER NOT NULL,
 artifact_json TEXT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_artifact_job FOREIGN KEY (job_id) REFERENCES ingestion_job(id),
 CONSTRAINT uk_artifact_attempt UNIQUE (job_id, attempt)
);
