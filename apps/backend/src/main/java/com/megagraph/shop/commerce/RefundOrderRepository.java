package com.megagraph.shop.commerce;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundOrderRepository extends JpaRepository<RefundOrder, Long> {
    Optional<RefundOrder> findByAfterSaleId(Long afterSaleId);
}
