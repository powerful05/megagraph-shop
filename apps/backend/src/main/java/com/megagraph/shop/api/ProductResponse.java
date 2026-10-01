package com.megagraph.shop.api;

import java.time.Instant;
import java.util.List;

public record ProductResponse(
        Long id,
        Long version,
        Long categoryId,
        String title,
        String subtitle,
        String draftStatus,
        String publishStatus,
        Instant publishedAt,
        List<ProductImageResponse> images,
        List<SkuResponse> skus,
        ProductEvidence evidence
) {
}
