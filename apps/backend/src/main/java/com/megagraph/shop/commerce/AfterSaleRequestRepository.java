package com.megagraph.shop.commerce;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AfterSaleRequestRepository extends JpaRepository<AfterSaleRequest, Long> {
    Optional<AfterSaleRequest> findByOrderId(Long orderId);
    Optional<AfterSaleRequest> findByUserIdAndRequestIdempotencyKey(Long userId, String requestIdempotencyKey);
    Optional<AfterSaleRequest> findByMerchantIdAndResolutionIdempotencyKey(Long merchantId, String key);
    boolean existsByOrderIdAndStatus(Long orderId, AfterSaleStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select request from AfterSaleRequest request where request.orderId = :orderId")
    Optional<AfterSaleRequest> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
