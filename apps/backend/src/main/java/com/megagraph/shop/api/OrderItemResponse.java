package com.megagraph.shop.api;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long productId,
        Long skuId,
        String productTitle,
        String skuCode,
        String skuName,
        BigDecimal unitPrice,
        String currency,
        Integer quantity,
        BigDecimal lineAmount,
        Long productVersion,
        SupplyDetails supplyDetails
) {
}
