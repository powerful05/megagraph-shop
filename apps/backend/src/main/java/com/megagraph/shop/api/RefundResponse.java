package com.megagraph.shop.api;

import java.math.BigDecimal;
import java.time.Instant;

public record RefundResponse(
        Long id,
        String provider,
        String providerRefundId,
        BigDecimal amount,
        String currency,
        String status,
        Instant refundedAt,
        String failureCode,
        String errorSummary
) {
}
