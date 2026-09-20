package com.megagraph.shop.commerce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "refund_order")
public class RefundOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long afterSaleId;
    private Long orderId;
    private Long paymentOrderId;
    private String provider;
    private String providerRefundId;
    private BigDecimal amount;

    @Column(columnDefinition = "CHAR(3)", length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    private RefundStatus status;

    private Instant refundedAt;
    private String failureCode;
    private String errorSummary;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;
    private Long lastChannelEventId;

    protected RefundOrder() {
    }

    public RefundOrder(
            Long afterSaleId,
            Long orderId,
            Long paymentOrderId,
            String provider,
            BigDecimal amount,
            String currency
    ) {
        this.afterSaleId = afterSaleId;
        this.orderId = orderId;
        this.paymentOrderId = paymentOrderId;
        this.provider = provider;
        this.amount = amount;
        this.currency = currency;
        this.status = RefundStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void bindProviderRefundId(String value) {
        if (providerRefundId == null) {
            providerRefundId = value;
        } else if (!providerRefundId.equals(value)) {
            throw new IllegalStateException("Provider refund id cannot change");
        }
    }

    public boolean markProcessing(Long channelEventId) {
        if (status == RefundStatus.PROCESSING) {
            return false;
        }
        if (status != RefundStatus.PENDING) {
            throw new IllegalStateException("Only pending refunds can start processing");
        }
        status = RefundStatus.PROCESSING;
        lastChannelEventId = channelEventId;
        return true;
    }

    public void succeed(Long channelEventId) {
        if (status != RefundStatus.PENDING && status != RefundStatus.PROCESSING) {
            throw new IllegalStateException("Refund is not processing");
        }
        status = RefundStatus.SUCCEEDED;
        refundedAt = Instant.now();
        failureCode = null;
        errorSummary = null;
        lastChannelEventId = channelEventId;
    }

    public void fail(Long channelEventId, String code, String summary) {
        if (status != RefundStatus.PENDING && status != RefundStatus.PROCESSING) {
            throw new IllegalStateException("Refund is not processing");
        }
        status = RefundStatus.FAILED;
        failureCode = code;
        errorSummary = summary;
        lastChannelEventId = channelEventId;
    }

    public void retry() {
        if (status != RefundStatus.FAILED) {
            throw new IllegalStateException("Only failed refunds can be retried");
        }
        status = RefundStatus.PENDING;
        failureCode = null;
        errorSummary = null;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderRefundId() {
        return providerRefundId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public Instant getRefundedAt() {
        return refundedAt;
    }

    public Long getAfterSaleId() {
        return afterSaleId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getPaymentOrderId() {
        return paymentOrderId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public Long getVersion() {
        return version;
    }
}
