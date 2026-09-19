package com.megagraph.shop.commerce;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesOrderItemRepository extends JpaRepository<SalesOrderItem, Long> {
    List<SalesOrderItem> findByOrderIdOrderByIdAsc(Long orderId);
}
