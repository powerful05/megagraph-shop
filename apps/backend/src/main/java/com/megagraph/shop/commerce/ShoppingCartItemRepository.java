package com.megagraph.shop.commerce;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShoppingCartItemRepository extends JpaRepository<ShoppingCartItem, Long> {
    List<ShoppingCartItem> findByCartIdOrderByIdAsc(Long cartId);
    Optional<ShoppingCartItem> findByCartIdAndSkuId(Long cartId, Long skuId);
    void deleteByCartIdAndSkuId(Long cartId, Long skuId);
    void deleteByCartId(Long cartId);
}
