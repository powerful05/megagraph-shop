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
    }

    public void succeed() {
        this.status = PaymentStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
    }

    public void fail() {
        this.status = PaymentStatus.FAILED;
        this.finishedAt = Instant.now();
    }

    public void cancel() {
        this.status = PaymentStatus.CANCELLED;
        this.finishedAt = Instant.now();
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
}
