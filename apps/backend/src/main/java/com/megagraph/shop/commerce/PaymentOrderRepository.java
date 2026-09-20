package com.megagraph.shop.commerce;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentOrderRepository extends JpaRepository<PaymentOrder, Long> {
    Optional<PaymentOrder> findByOrderId(Long orderId);
    Optional<PaymentOrder> findByProviderAndProviderPaymentId(String provider, String providerPaymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from PaymentOrder payment where payment.orderId = :orderId")
    Optional<PaymentOrder> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from PaymentOrder payment where payment.id = :id")
    Optional<PaymentOrder> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select payment from PaymentOrder payment where payment.provider = :provider and payment.providerPaymentId = :resourceId")
    Optional<PaymentOrder> findByProviderResourceForUpdate(
            @Param("provider") String provider,
            @Param("resourceId") String resourceId
    );
}
