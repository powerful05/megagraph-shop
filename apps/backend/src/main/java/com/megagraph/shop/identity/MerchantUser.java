package com.megagraph.shop.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "merchant_user")
public class MerchantUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long merchantId;
    private Long userId;

    @Enumerated(EnumType.STRING)
    private MerchantRole role;

    private Instant createdAt;

    protected MerchantUser() {
    }

    public MerchantUser(Long merchantId, Long userId, MerchantRole role) {
        this.merchantId = merchantId;
        this.userId = userId;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public Long getUserId() {
        return userId;
    }

    public MerchantRole getRole() {
        return role;
    }
}

