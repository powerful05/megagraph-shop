package com.megagraph.shop.api;

import java.math.BigDecimal;

public record CartItemResponse(
        Long skuId,
        Long productId,
        Long merchantId,
        String productTitle,
        String skuCode,
        String skuName,
        BigDecimal unitPrice,
        String currency,
        Integer quantity,
        BigDecimal lineAmount,
        Integer availableStock
) {
}
