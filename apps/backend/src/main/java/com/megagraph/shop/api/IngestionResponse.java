package com.megagraph.shop.api;
import java.time.Instant;
import java.util.List;
public record IngestionResponse(Long id, Long productId, String status, String originalFilename,
 Instant createdAt, Instant finishedAt, List<IngestionStepResponse> steps,
 long version, int attempt, Instant leaseUntil, Instant nextAttemptAt,
 String errorCode, String errorSummary, IngestionArtifactResponse artifact) {}
