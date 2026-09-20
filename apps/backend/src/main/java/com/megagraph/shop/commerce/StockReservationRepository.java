package com.megagraph.shop.commerce;

import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {
    List<StockReservation> findByOrderIdOrderByIdAsc(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select reservation from StockReservation reservation where reservation.orderId = :orderId order by reservation.id")
    List<StockReservation> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
