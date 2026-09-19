package com.megagraph.shop.commerce;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {
    List<StockReservation> findByOrderIdOrderByIdAsc(Long orderId);
}
