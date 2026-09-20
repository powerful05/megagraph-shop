package com.megagraph.shop.commerce;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MockRefundGateway implements RefundGateway {

    @Override
    public RefundResult refund(
            String providerPaymentId,
            BigDecimal amount,
            String currency,
            String idempotencyKey
    ) {
        String source = providerPaymentId + ":" + amount + ":" + currency + ":" + idempotencyKey;
        String refundId = "mock_refund_" + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
        return new RefundResult("MOCK", refundId.replace("-", ""));
    }
}
