package com.megagraph.shop.api;

import java.time.Instant;

public record AfterSaleResponse(
        Long id,
        String status,
        String reason,
        String merchantNote,
        Instant requestedAt,
        Instant resolvedAt,
        RefundResponse refund
) {
}
