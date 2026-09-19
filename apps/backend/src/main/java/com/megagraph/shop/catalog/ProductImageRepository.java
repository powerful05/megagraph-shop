package com.megagraph.shop.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {
    List<ProductImage> findByProductIdOrderBySortOrderAsc(Long productId);
    Optional<ProductImage> findFirstByProductIdOrderBySortOrderAsc(Long productId);
}

