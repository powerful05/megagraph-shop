package com.megagraph.shop.ingestion;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionStepRepository extends JpaRepository<IngestionStep, Long> {
    List<IngestionStep> findByJobIdOrderByIdAsc(Long jobId);
}

