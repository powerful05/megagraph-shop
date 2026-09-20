package com.megagraph.shop.commerce;

import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundOrderRepository extends JpaRepository<RefundOrder, Long> {
    Optional<RefundOrder> findByAfterSaleId(Long afterSaleId);
    Optional<RefundOrder> findByProviderAndProviderRefundId(String provider, String providerRefundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select refund from RefundOrder refund where refund.id = :id")
    Optional<RefundOrder> findByIdForUpdate(@Param("id") Long id);
}
