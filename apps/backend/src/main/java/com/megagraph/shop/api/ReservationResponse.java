package com.megagraph.shop.api;

public record ReservationResponse(
        Long skuId,
        Integer quantity,
        String status
) {
}
