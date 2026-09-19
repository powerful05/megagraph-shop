package com.megagraph.shop.catalog;

import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductSkuRepository extends JpaRepository<ProductSku, Long> {
    List<ProductSku> findByProductIdAndDeletedAtIsNullOrderByIdAsc(Long productId);
    Optional<ProductSku> findFirstByProductIdAndDeletedAtIsNullOrderByIdAsc(Long productId);
    boolean existsByProductIdAndStatusAndDeletedAtIsNullAndAvailableStockGreaterThan(
            Long productId,
            SkuStatus status,
            int stock
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select sku from ProductSku sku where sku.id in :ids order by sku.id")
    List<ProductSku> findAllByIdForUpdate(@Param("ids") List<Long> ids);
}
