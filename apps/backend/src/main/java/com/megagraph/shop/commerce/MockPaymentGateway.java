package com.megagraph.shop.commerce;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentGateway implements PaymentGateway {

    @Override
    public PaymentSession createPayment(String orderNo, BigDecimal amount, String currency) {
        return new PaymentSession("MOCK", "mock_" + UUID.randomUUID().toString().replace("-", ""));
    }
}
