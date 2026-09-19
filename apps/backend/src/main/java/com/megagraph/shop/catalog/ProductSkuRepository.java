package com.megagraph.shop.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSkuRepository extends JpaRepository<ProductSku, Long> {
    List<ProductSku> findByProductIdAndDeletedAtIsNullOrderByIdAsc(Long productId);
    Optional<ProductSku> findFirstByProductIdAndDeletedAtIsNullOrderByIdAsc(Long productId);
    boolean existsByProductIdAndStatusAndDeletedAtIsNullAndAvailableStockGreaterThan(
            Long productId,
            SkuStatus status,
            int stock
    );
}

