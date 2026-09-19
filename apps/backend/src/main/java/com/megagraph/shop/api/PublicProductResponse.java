package com.megagraph.shop.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PublicProductResponse(
        Long id,
        String title,
        String subtitle,
        Long categoryId,
        BigDecimal priceFrom,
        String currency,
        Integer availableStock,
        String mainImageUrl,
        Instant publishedAt,
        List<PublicSkuResponse> skus
) {
}
