package com.megagraph.shop.payment;

import java.math.BigDecimal;

public record RefundSubmission(
        String providerPaymentId,
        String providerRefundId,
        BigDecimal amount,
        String currency
) {
}
