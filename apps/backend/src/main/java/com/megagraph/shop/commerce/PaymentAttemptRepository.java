package com.megagraph.shop.commerce;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, Long> {
    Optional<PaymentAttempt> findByPaymentOrderIdAndIdempotencyKey(Long paymentOrderId, String idempotencyKey);
}
