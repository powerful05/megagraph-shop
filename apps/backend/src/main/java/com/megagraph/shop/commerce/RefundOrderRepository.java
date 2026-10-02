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

    @Query("select r.orderId from RefundOrder r where r.provider=:provider and r.providerRefundId=:resource")
    Optional<Long> resourceOrderId(@Param("provider") String provider,@Param("resource") String resource);
    @Query("select r.id from RefundOrder r where r.provider=:provider and r.providerRefundId=:resource")
    Optional<Long> resourceRefundId(@Param("provider") String provider,@Param("resource") String resource);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select refund from RefundOrder refund where refund.id = :id")
    Optional<RefundOrder> findByIdForUpdate(@Param("id") Long id);
}
