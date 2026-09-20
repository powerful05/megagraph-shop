package com.megagraph.shop.commerce;

import java.math.BigDecimal;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local & !test")
public class UnavailableRefundGateway implements RefundGateway {
    @Override
    public RefundResult refund(
            String providerPaymentId,
            BigDecimal amount,
            String currency,
            String idempotencyKey
    ) {
        throw new IllegalStateException("No production refund gateway is configured");
    }
}
