package com.megagraph.shop.api;

import java.math.BigDecimal;
import java.util.List;

public record CartResponse(
        Long cartId,
        String currency,
        BigDecimal estimatedTotal,
        List<CartItemResponse> items
) {
}
