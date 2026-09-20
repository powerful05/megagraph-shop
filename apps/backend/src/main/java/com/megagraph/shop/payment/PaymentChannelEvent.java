package com.megagraph.shop.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payment_channel_event")
public class PaymentChannelEvent {

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
    private BigDecimal amount;
    @Column(columnDefinition = "CHAR(3)", length = 3)
    private String currency;
    @Column(columnDefinition = "CHAR(64)", length = 64)
    private String payloadDigest;
    @Column(columnDefinition = "TEXT")
    private String payloadText;
    private Instant occurredAt;
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    private ChannelEventProcessingStatus processingStatus;

    private Instant processedAt;
    private Long matchedPaymentOrderId;
    private Long matchedRefundOrderId;
    private String failureCode;
    private Integer conflictCount;
    @Column(columnDefinition = "CHAR(64)", length = 64)
    private String lastConflictDigest;
    private Instant lastConflictAt;

    @Version
    private Long version;

    protected PaymentChannelEvent() {
    }

    public PaymentChannelEvent(ChannelEvent event, String payloadText) {
        this.provider = event.provider();
        this.providerEventId = event.providerEventId();
        this.eventType = event.eventType();
        this.resourceType = event.resourceType();
        this.providerResourceId = event.providerResourceId();
        this.amount = event.amount();
        this.currency = event.currency();
        this.payloadDigest = event.payloadDigest();
        this.payloadText = payloadText;
        this.occurredAt = event.occurredAt();
        this.receivedAt = Instant.now();
        this.processingStatus = ChannelEventProcessingStatus.RECEIVED;
        this.conflictCount = 0;
    }

    public boolean hasDigest(String digest) {
        return payloadDigest.equals(digest);
    }

    public void markApplied(Long paymentOrderId, Long refundOrderId) {
        this.processingStatus = ChannelEventProcessingStatus.APPLIED;
        this.matchedPaymentOrderId = paymentOrderId;
        this.matchedRefundOrderId = refundOrderId;
        this.failureCode = null;
        this.processedAt = Instant.now();
    }

    public void markReviewRequired(String code, Long paymentOrderId, Long refundOrderId) {
        this.processingStatus = ChannelEventProcessingStatus.REVIEW_REQUIRED;
        this.failureCode = code;
        this.matchedPaymentOrderId = paymentOrderId;
        this.matchedRefundOrderId = refundOrderId;
        this.processedAt = Instant.now();
    }

    public void markRejected(String code) {
        this.processingStatus = ChannelEventProcessingStatus.REJECTED;
        this.failureCode = code;
        this.processedAt = Instant.now();
    }

    public boolean recordPayloadConflict(String digest) {
        boolean firstConflict = this.processingStatus != ChannelEventProcessingStatus.REVIEW_REQUIRED;
        this.processingStatus = ChannelEventProcessingStatus.REVIEW_REQUIRED;
        this.failureCode = "EVENT_ID_PAYLOAD_MISMATCH";
        this.conflictCount += 1;
        this.lastConflictDigest = digest;
        this.lastConflictAt = Instant.now();
        this.processedAt = this.lastConflictAt;
        return firstConflict;
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

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getPayloadDigest() {
        return payloadDigest;
    }

    public ChannelEventProcessingStatus getProcessingStatus() {
        return processingStatus;
    }

    public Long getMatchedPaymentOrderId() {
        return matchedPaymentOrderId;
    }

    public Long getMatchedRefundOrderId() {
        return matchedRefundOrderId;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public Integer getConflictCount() {
        return conflictCount;
    }

    public Long getVersion() {
        return version;
    }
}
