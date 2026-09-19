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

