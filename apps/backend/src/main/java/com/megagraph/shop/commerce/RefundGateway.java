package com.megagraph.shop.commerce;

import java.math.BigDecimal;

public interface RefundGateway {
    RefundResult refund(
            String providerPaymentId,
            BigDecimal amount,
            String currency,
            String idempotencyKey
    );
}
