package com.megagraph.shop.commerce;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "checkout_group")
public class CheckoutGroup {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String groupNo;
    private Long userId;
    private String idempotencyKey;
    @Column(columnDefinition = "CHAR(64)", length = 64)
    private String requestFingerprint;
    @Column(columnDefinition = "CHAR(3)", length = 3)
    private String currency;
    private BigDecimal totalAmount;
    private Instant createdAt;

    protected CheckoutGroup() {}
    public CheckoutGroup(Long userId, String key, String fingerprint, String currency, BigDecimal totalAmount) {
        this.groupNo = "CG" + UUID.randomUUID().toString().replace("-", "");
        this.userId = userId; this.idempotencyKey = key; this.requestFingerprint = fingerprint;
        this.currency = currency; this.totalAmount = totalAmount; this.createdAt = Instant.now();
    }
    public Long getId() { return id; }
    public String getGroupNo() { return groupNo; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getCurrency() { return currency; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public Instant getCreatedAt() { return createdAt; }
}
