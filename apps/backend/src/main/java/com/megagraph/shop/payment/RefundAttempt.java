package com.megagraph.shop.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "refund_attempt")
public class RefundAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long refundOrderId;
    private String idempotencyKey;
    private Integer attemptNo;

    @Enumerated(EnumType.STRING)
    private RefundAttemptStatus status;

    private Instant createdAt;
    private Instant updatedAt;

    protected RefundAttempt() {
    }

    public RefundAttempt(Long refundOrderId, String idempotencyKey, int attemptNo) {
        this.refundOrderId = refundOrderId;
        this.idempotencyKey = idempotencyKey;
        this.attemptNo = attemptNo;
        this.status = RefundAttemptStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void markProcessing() {
        this.status = RefundAttemptStatus.PROCESSING;
    }

    public void markSucceeded() {
        this.status = RefundAttemptStatus.SUCCEEDED;
    }

    public void markFailed() {
        this.status = RefundAttemptStatus.FAILED;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getRefundOrderId() {
        return refundOrderId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public RefundAttemptStatus getStatus() {
        return status;
    }
}
