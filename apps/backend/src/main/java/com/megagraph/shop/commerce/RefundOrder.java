package com.megagraph.shop.commerce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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
    private Instant createdAt;

    protected RefundOrder() {
    }

    public RefundOrder(
            Long afterSaleId,
            Long orderId,
            Long paymentOrderId,
            String provider,
            String providerRefundId,
            BigDecimal amount,
            String currency
    ) {
        this.afterSaleId = afterSaleId;
        this.orderId = orderId;
        this.paymentOrderId = paymentOrderId;
        this.provider = provider;
        this.providerRefundId = providerRefundId;
        this.amount = amount;
        this.currency = currency;
        this.status = RefundStatus.SUCCEEDED;
        this.refundedAt = Instant.now();
        this.createdAt = this.refundedAt;
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
}
