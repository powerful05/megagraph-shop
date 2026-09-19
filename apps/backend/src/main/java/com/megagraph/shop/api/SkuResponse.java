package com.megagraph.shop.api;

import java.math.BigDecimal;

public record SkuResponse(
        Long id,
        String code,
        String name,
        BigDecimal price,
        String currency,
        Integer stock,
        String status
) {
}

