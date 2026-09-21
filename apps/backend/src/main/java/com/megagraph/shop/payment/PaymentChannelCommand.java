package com.megagraph.shop.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_channel_command")
public class PaymentChannelCommand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String provider;
    private String providerEventId;

    @Enumerated(EnumType.STRING)
    private ChannelEventType eventType;

    @Enumerated(EnumType.STRING)
    private ChannelResourceType resourceType;

    private String providerResourceId;
    private Long paymentOrderId;
    private Long refundOrderId;
    private Long refundAttemptId;
    private BigDecimal amount;
    @Column(columnDefinition = "CHAR(3)", length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    private ChannelCommandStatus status;

    private Integer attemptCount;
    private Instant nextAttemptAt;
    private Instant claimedAt;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;

    protected PaymentChannelCommand() {
    }

    public PaymentChannelCommand(
            String provider,
            String providerEventId,
            ChannelEventType eventType,
            ChannelResourceType resourceType,
            String providerResourceId,
            Long paymentOrderId,
            Long refundOrderId,
            Long refundAttemptId,
            BigDecimal amount,
            String currency
    ) {
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.eventType = eventType;
        this.resourceType = resourceType;
        this.providerResourceId = providerResourceId;
        this.paymentOrderId = paymentOrderId;
        this.refundOrderId = refundOrderId;
        this.refundAttemptId = refundAttemptId;
        this.amount = amount;
        this.currency = currency;
        this.status = ChannelCommandStatus.PENDING;
        this.attemptCount = 0;
        this.nextAttemptAt = Instant.now();
        this.createdAt = this.nextAttemptAt;
        this.updatedAt = this.nextAttemptAt;
    }

    public void claim(Instant now) {
        this.status = ChannelCommandStatus.PROCESSING;
        this.attemptCount += 1;
        this.claimedAt = now;
    }

    public void complete() {
        this.status = ChannelCommandStatus.COMPLETED;
        this.completedAt = Instant.now();
        this.lastError = null;
        this.claimedAt = null;
    }

    public void retry(String error, Instant nextAttemptAt, int maxAttempts) {
        this.lastError = error == null ? "Unknown mock channel failure" : error.substring(0, Math.min(500, error.length()));
        this.nextAttemptAt = nextAttemptAt;
        this.status = attemptCount >= maxAttempts ? ChannelCommandStatus.FAILED : ChannelCommandStatus.PENDING;
        this.claimedAt = null;
    }

    public void reclaim(Instant now) {
        if (status != ChannelCommandStatus.PROCESSING) {
            throw new IllegalStateException("Only processing commands can be reclaimed");
        }
        attemptCount += 1;
        claimedAt = now;
    }

    public void bindProviderResourceId(String value) {
        if (providerResourceId == null) {
            this.providerResourceId = value;
        } else if (!providerResourceId.equals(value)) {
            throw new IllegalStateException("Provider resource id cannot change between attempts");
        }
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderEventId() {
        return providerEventId;
    }

    public ChannelEventType getEventType() {
        return eventType;
    }

    public ChannelResourceType getResourceType() {
        return resourceType;
    }

    public String getProviderResourceId() {
        return providerResourceId;
    }

    public Long getPaymentOrderId() {
        return paymentOrderId;
    }

    public Long getRefundOrderId() {
        return refundOrderId;
    }

    public Long getRefundAttemptId() {
        return refundAttemptId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public ChannelCommandStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
