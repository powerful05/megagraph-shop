package com.megagraph.shop.ingestion;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, Long> {
    Optional<IngestionJob> findByMerchantIdAndIdempotencyKey(Long merchantId, String idempotencyKey);
    Optional<IngestionJob> findByIdAndMerchantId(Long id, Long merchantId);
}

