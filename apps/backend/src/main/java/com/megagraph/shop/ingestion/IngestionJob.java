package com.megagraph.shop.ingestion;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ingestion_job")
public class IngestionJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @jakarta.persistence.Version
    private Long version;
    private Integer attempt = 0;
    private Integer retryCount = 0;
    private Instant leaseUntil;
    private Instant nextAttemptAt;
    private String inputHash;
    private String payloadHash;
    private String storageKey;
    private String contentType;
    private Long byteSize;
    private String mockScenario = "success";
    private Long merchantId;
    private Long productId;
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    private IngestionStatus status;

    private String originalFilename;
    private String errorCode;
    private String errorSummary;
    private Long createdBy;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant createdAt;

    protected IngestionJob() {
    }

    public IngestionJob(Long merchantId, String idempotencyKey, String originalFilename, Long createdBy) {
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.originalFilename = originalFilename;
        this.createdBy = createdBy;
        this.status = IngestionStatus.QUEUED;
        this.createdAt = Instant.now();
    }

    public void input(String inputHash, String payloadHash, com.megagraph.shop.media.StoredMedia media, String scenario) {
        this.inputHash = inputHash; this.payloadHash = payloadHash; this.storageKey = media.storageKey();
        this.contentType = media.contentType(); this.byteSize = media.byteSize(); this.mockScenario = scenario;
    }
    public Long getVersion() { return version; }
    public Integer getAttempt() { return attempt; }
    public Instant getLeaseUntil() { return leaseUntil; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getPayloadHash() { return payloadHash; }
    public String getStorageKey() { return storageKey; }
    public String getContentType() { return contentType; }
    public void start() {
        this.status = IngestionStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public void succeed(Long productId) {
        this.productId = productId;
        this.status = IngestionStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public Long getProductId() {
        return productId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public IngestionStatus getStatus() {
        return status;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}

