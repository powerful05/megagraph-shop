package com.megagraph.shop.commerce;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "payment_attempt")
public class PaymentAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long paymentOrderId;
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    private MockPaymentResult requestedResult;

    private Instant createdAt;

    protected PaymentAttempt() {
    }

    public PaymentAttempt(Long paymentOrderId, String idempotencyKey, MockPaymentResult requestedResult) {
        this.paymentOrderId = paymentOrderId;
        this.idempotencyKey = idempotencyKey;
        this.requestedResult = requestedResult;
        this.createdAt = Instant.now();
    }
}
