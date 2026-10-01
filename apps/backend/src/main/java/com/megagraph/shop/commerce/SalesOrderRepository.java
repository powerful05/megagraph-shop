package com.megagraph.shop.commerce;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {
    Optional<SalesOrder> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
    Optional<SalesOrder> findByIdAndUserId(Long id, Long userId);
    List<SalesOrder> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<SalesOrder> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);
    List<SalesOrder> findByCheckoutGroupIdOrderByIdAsc(Long checkoutGroupId);
    List<SalesOrder> findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(OrderStatus status, Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select orders from SalesOrder orders where orders.id = :id")
    Optional<SalesOrder> findByIdForUpdate(@Param("id") Long id);
}
