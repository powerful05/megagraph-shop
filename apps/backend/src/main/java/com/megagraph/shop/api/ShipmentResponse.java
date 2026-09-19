package com.megagraph.shop.api;

import java.time.Instant;

public record ShipmentResponse(
        Long id,
        String carrierName,
        String trackingNo,
        String status,
        Instant shippedAt,
        Instant deliveredAt
) {
}
