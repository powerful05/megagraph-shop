package com.megagraph.shop.api;

import java.math.BigDecimal;
import java.time.Instant;

public record PublicProductResponse(
        Long id,
        String title,
        String subtitle,
        Long categoryId,
        BigDecimal priceFrom,
        String currency,
        Integer availableStock,
        String mainImageUrl,
        Instant publishedAt
) {
}

