package com.megagraph.shop.identity;

import jakarta.persistence.Entity;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.Instant;

@Entity
public class TrialInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 64)
    private String codeHash;
    private Instant expiresAt;
    private Instant redeemedAt;
    private Long redeemedBy;
    private Instant createdAt;

    protected TrialInvite() {
    }

    public TrialInvite(String codeHash, Instant expiresAt) {
        this.codeHash = codeHash;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRedeemedAt() {
        return redeemedAt;
    }

    public void redeem(Long userId) {
        this.redeemedBy = userId;
        this.redeemedAt = Instant.now();
    }
}
