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
@Table(name = "payment_order")
public class PaymentOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long orderId;
    private String provider;
    private String providerPaymentId;
    private BigDecimal amount;

    @Column(columnDefinition = "CHAR(3)", length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    private PaymentStatus status;

    private Instant finishedAt;
    private Long lastChannelEventId;
    private Instant statusChangedAt;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    protected PaymentOrder() {
    }

    public PaymentOrder(
            Long orderId,
            String provider,
            String providerPaymentId,
            BigDecimal amount,
            String currency
    ) {
        this.orderId = orderId;
        this.provider = provider;
        this.providerPaymentId = providerPaymentId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.statusChangedAt = this.createdAt;
    }

    public boolean markProcessing(Long channelEventId) {
        if (status == PaymentStatus.PROCESSING) {
            return false;
        }
        if (status != PaymentStatus.PENDING) {
            throw new IllegalStateException("Only pending payments can start processing");
        }
        this.status = PaymentStatus.PROCESSING;
        this.lastChannelEventId = channelEventId;
        this.statusChangedAt = Instant.now();
        return true;
    }

    public void succeed(Long channelEventId) {
        this.status = PaymentStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
        this.statusChangedAt = this.finishedAt;
        this.lastChannelEventId = channelEventId;
    }

    public void fail(Long channelEventId) {
        this.status = PaymentStatus.FAILED;
        this.finishedAt = Instant.now();
        this.statusChangedAt = this.finishedAt;
        this.lastChannelEventId = channelEventId;
    }

    public void cancel(Long channelEventId) {
        this.status = PaymentStatus.CANCELLED;
        this.finishedAt = Instant.now();
        this.statusChangedAt = this.finishedAt;
        this.lastChannelEventId = channelEventId;
    }

    public void cancel() {
        cancel(null);
    }

    public void refund() {
        if (status != PaymentStatus.SUCCEEDED) {
            throw new IllegalStateException("Only succeeded payments can be refunded");
        }
        this.status = PaymentStatus.REFUNDED;
        this.finishedAt = Instant.now();
        this.statusChangedAt = this.finishedAt;
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

    public String getProvider() {
        return provider;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Long getVersion() {
        return version;
    }
}
