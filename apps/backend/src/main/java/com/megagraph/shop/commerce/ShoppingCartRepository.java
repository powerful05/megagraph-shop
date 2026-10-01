package com.megagraph.shop.commerce;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShoppingCartRepository extends JpaRepository<ShoppingCart, Long> {
    Optional<ShoppingCart> findByUserId(Long userId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select cart from ShoppingCart cart where cart.userId = :userId")
    Optional<ShoppingCart> findByUserIdForUpdate(@org.springframework.data.repository.query.Param("userId") Long userId);
}
