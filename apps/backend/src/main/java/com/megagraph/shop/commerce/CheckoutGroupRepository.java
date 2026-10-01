package com.megagraph.shop.commerce;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface CheckoutGroupRepository extends JpaRepository<CheckoutGroup, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<CheckoutGroup> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    Optional<CheckoutGroup> findByIdAndUserId(Long id, Long userId);
}
