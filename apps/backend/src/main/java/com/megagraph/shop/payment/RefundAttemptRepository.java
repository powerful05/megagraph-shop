package com.megagraph.shop.payment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundAttemptRepository extends JpaRepository<RefundAttempt, Long> {
    Optional<RefundAttempt> findByRefundOrderIdAndIdempotencyKey(Long refundOrderId, String idempotencyKey);
    Optional<RefundAttempt> findFirstByRefundOrderIdOrderByAttemptNoDesc(Long refundOrderId);
    long countByRefundOrderId(Long refundOrderId);
}
