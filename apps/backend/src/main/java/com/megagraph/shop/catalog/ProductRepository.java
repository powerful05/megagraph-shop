package com.megagraph.shop.catalog;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByIdAndMerchantIdAndDeletedAtIsNull(Long id, Long merchantId);
    List<Product> findByMerchantIdAndDeletedAtIsNullOrderByUpdatedAtDesc(Long merchantId);
    List<Product> findByPublishStatusAndDeletedAtIsNullOrderByPublishedAtDesc(PublishStatus publishStatus);
}

