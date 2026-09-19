package com.megagraph.shop.api;

import java.math.BigDecimal;

public record PublicSkuResponse(
        Long id,
        String code,
        String name,
        BigDecimal price,
        String currency,
        Integer availableStock
) {
}
