package com.megagraph.shop.commerce;

import com.megagraph.shop.shared.ConflictException;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "after_sale_request")
public class AfterSaleRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orderId;
    private Long userId;
    private Long merchantId;
    private String requestIdempotencyKey;
    private String reason;

    @Enumerated(EnumType.STRING)
    private AfterSaleStatus status;

    private String resolutionIdempotencyKey;
    private String merchantNote;
    private Instant requestedAt;
    private Instant resolvedAt;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    protected AfterSaleRequest() {
    }

    public AfterSaleRequest(
            Long orderId,
            Long userId,
            Long merchantId,
            String requestIdempotencyKey,
            String reason
    ) {
        this.orderId = orderId;
        this.userId = userId;
        this.merchantId = merchantId;
        this.requestIdempotencyKey = requestIdempotencyKey;
        this.reason = reason;
        this.status = AfterSaleStatus.REQUESTED;
        this.requestedAt = Instant.now();
        this.createdAt = this.requestedAt;
        this.updatedAt = this.requestedAt;
    }

    public void markRefunded(String idempotencyKey, String note) {
        if (status != AfterSaleStatus.REFUND_PROCESSING) {
            throw new ConflictException("AFTER_SALE_NOT_PROCESSING", "退款申请不在处理中");
        }
        this.status = AfterSaleStatus.REFUNDED;
        if (idempotencyKey != null) {
            this.resolutionIdempotencyKey = idempotencyKey;
        }
        if (note != null) {
            this.merchantNote = note;
        }
        this.resolvedAt = Instant.now();
    }

    public void startRefund(String idempotencyKey, String note) {
        requireRequested();
        this.status = AfterSaleStatus.REFUND_PROCESSING;
        this.resolutionIdempotencyKey = idempotencyKey;
        this.merchantNote = note;
    }

    public void markRefundFailed() {
        if (status != AfterSaleStatus.REFUND_PROCESSING) {
            throw new ConflictException("AFTER_SALE_NOT_PROCESSING", "退款申请不在处理中");
        }
        this.status = AfterSaleStatus.REFUND_FAILED;
    }

    public void retryRefund(String idempotencyKey, String note) {
        if (status != AfterSaleStatus.REFUND_FAILED) {
            throw new ConflictException("AFTER_SALE_NOT_RETRYABLE", "只有失败退款可以重试");
        }
        this.status = AfterSaleStatus.REFUND_PROCESSING;
        this.resolutionIdempotencyKey = idempotencyKey;
        this.merchantNote = note;
        this.resolvedAt = null;
    }

    public void reject(String idempotencyKey, String note) {
        requireRequested();
        this.status = AfterSaleStatus.REJECTED;
        this.resolutionIdempotencyKey = idempotencyKey;
        this.merchantNote = note;
        this.resolvedAt = Instant.now();
    }

    private void requireRequested() {
        if (status != AfterSaleStatus.REQUESTED) {
            throw new ConflictException("AFTER_SALE_ALREADY_RESOLVED", "退款申请已经处理");
        }
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public String getRequestIdempotencyKey() {
        return requestIdempotencyKey;
    }

    public String getReason() {
        return reason;
    }

    public AfterSaleStatus getStatus() {
        return status;
    }

    public String getResolutionIdempotencyKey() {
        return resolutionIdempotencyKey;
    }

    public String getMerchantNote() {
        return merchantNote;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Long getVersion() {
        return version;
    }
}
